#!/usr/bin/env python3
"""Summarise a scripts/bench-pod-ready.sh run: reads <dir>/raw/<arm>-<round>.{json,fetch.log,app.log} and prints Markdown tables.

Pod timestamps in the Kubernetes API have a resolution of one second, so the coarse phases are whole seconds. The finer
numbers come from the tools' own timing lines (MNEMO_WARMUP_TIMING=1) and from Spring's "Started ... in N seconds".
"""
import csv
import json
import re
import statistics
import sys
from datetime import datetime
from pathlib import Path

ARMS = ["none", "nocache", "cached"]
ARM_NOTE = {
    "none": "no mnemo-warmup",
    "nocache": "mnemo-warmup on, no archive in the store",
    "cached": "mnemo-warmup on, archive trained",
}


def ts(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")) if value else None


def secs(a, b):
    return None if a is None or b is None else (b - a).total_seconds()


def condition(pod, kind):
    for c in pod["status"].get("conditions", []):
        if c["type"] == kind:
            return ts(c.get("lastTransitionTime"))
    return None


def container_times(statuses, name):
    for s in statuses or []:
        if s["name"] == name:
            st = s.get("state", {})
            if "terminated" in st:
                return ts(st["terminated"].get("startedAt")), ts(st["terminated"].get("finishedAt"))
            if "running" in st:
                return ts(st["running"].get("startedAt")), None
    return None, None


def ms(pattern, text):
    m = re.search(pattern, text)
    return float(m.group(1)) if m else None


def load(raw_dir):
    rows = []
    for j in sorted(raw_dir.glob("*.json")):
        arm, _, rnd = j.stem.rpartition("-")
        if rnd == "warmup":
            continue
        pod = json.loads(j.read_text())
        fetch = (raw_dir / f"{j.stem}.fetch.log").read_text(errors="replace") if (raw_dir / f"{j.stem}.fetch.log").exists() else ""
        app = (raw_dir / f"{j.stem}.app.log").read_text(errors="replace")
        created, started, ready = ts(pod["metadata"]["creationTimestamp"]), ts(pod["status"].get("startTime")), condition(pod, "Ready")
        initialized = condition(pod, "Initialized")
        inst = container_times(pod["status"].get("initContainerStatuses"), "mnemo-warmup-install")
        fet = container_times(pod["status"].get("initContainerStatuses"), "mnemo-warmup-fetch")
        app_start, _ = container_times(pod["status"].get("containerStatuses"), "app")
        row = {
            "arm": arm,
            "round": int(rnd),
            "scheduling": secs(created, started),
            "install_init": secs(*inst) if inst[0] and inst[1] else None,
            "fetch_init": secs(*fet) if fet[0] and fet[1] else None,
            "container_start": secs(initialized, app_start),
            "app_to_ready": secs(app_start, ready),
            "total_from_start": secs(started, ready),
            "total_from_creation": secs(created, ready),
            "archive_used": bool(re.search(r"run: using (APP_CDS|LEYDEN_AOT) archive", app)),
            "fetch_hit": "Archive:" in fetch,
            # fine-grained, milliseconds
            "fetch_process": ms(r"timing: fetch-process=(\d+) ms", fetch),
            "fetch_jdk_detect": ms(r"fetch: timing:.*?jdk-detect=(\d+) ms", fetch),
            "fetch_hash_classpath": ms(r"fetch: timing:.*?extract-and-hash-classpath=(\d+) ms", fetch),
            "fetch_store_get": ms(r"fetch: timing:.*?store-lookup-download-verify=(\d+) ms", fetch),
            "fetch_tool_uptime": ms(r"fetch: timing:.*?tool-jvm-uptime=(\d+) ms", fetch),
            "plan_process": ms(r"timing: plan-process=(\d+) ms", app),
            "run_read_manifest": ms(r"run: timing:.*?read-manifest=(\d+) ms", app),
            "run_archive_sha": ms(r"run: timing:.*?archive-sha256=(\d+) ms", app),
            "run_jdk_probe": ms(r"run: timing:.*?jdk-probe=(\d+) ms", app),
            "run_classpath_hash": ms(r"run: timing:.*?classpath-hash=(\d+) ms", app),
            "run_tool_uptime": ms(r"run: timing:.*?tool-jvm-uptime=(\d+) ms", app),
            "jvm_started_in": (ms(r"Started \w+ in ([\d.]+) seconds", app) or 0) * 1000 or None,
        }
        rows.append(row)
    return rows


def stat(values, digits=1):
    vals = [v for v in values if v is not None]
    if not vals:
        return "n/a"
    med = statistics.median(vals)
    fmt = f"{{:.{digits}f}}"
    return f"{fmt.format(med)} ({fmt.format(min(vals))}-{fmt.format(max(vals))})"


def med(rows, key):
    vals = [r[key] for r in rows if r[key] is not None]
    return statistics.median(vals) if vals else None


def write_csv(path, rows):
    if not rows:
        return
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        for r in sorted(rows, key=lambda r: (r["arm"], r["round"])):
            w.writerow(r)


def registry_latency(path):
    if not path.exists():
        return None
    rtt, dl = [], []
    for line in path.read_text().splitlines():
        if line.startswith("rtt_ms"):
            rtt.append(float(line.split()[1]))
        elif line.startswith("download_bytes"):
            _, b, _, t = line.split()
            dl.append(int(b) / float(t) / 1e6 if float(t) else 0)
    if not rtt:
        return None
    rtt.sort()
    text = f"in-cluster registry:2 over plain HTTP, request time of GET /v2/ from the node: median {statistics.median(rtt):.1f} ms, max {rtt[-1]:.1f} ms ({len(rtt)} samples)"
    if dl:
        text += f"; archive download from the node {statistics.median(dl):.0f} MB/s median ({len(dl)} samples)"
    return text + ". This is a best case: the registry is one network hop away, not a remote one."


def main(out):
    out = Path(out)
    rows = load(out / "raw")
    by = {a: [r for r in rows if r["arm"] == a] for a in ARMS}
    env = json.loads((out / "environment.json").read_text()) if (out / "environment.json").exists() else {}
    write_csv(out / "results.csv", rows)
    run, kn, node, host = env.get("run", {}), env.get("kubernetes", {}), env.get("kind_node", {}), env.get("host", {})

    print("## Pod-to-Ready breakdown\n")
    if env:
        arch = kn.get("node_info", {}).get("architecture")
        print(f"**Application:** {run.get('app')} ({run.get('app_jars')} JARs, {int(run.get('app_kib', 0) or 0) / 1024:.0f} MiB; "
              f"archive {int(run.get('archive_bytes', 0) or 0) / 1e6:.0f} MB); {run.get('jdk')}; "
              f"limits cpu={run.get('app_cpu_limit')} memory={run.get('app_memory_limit')}.\n")
        print(f"**Machine:** {host.get('cpu_model')}, {host.get('logical_cpus')} logical CPUs, {int(host.get('memory_bytes') or 0) / 2**30:.0f} GiB, {host.get('os')}. "
              f"Docker gives the VM {env.get('docker', {}).get('cpus_available_to_docker')} CPUs and "
              f"{int(env.get('docker', {}).get('memory_available_to_docker_bytes') or 0) / 2**30:.1f} GiB; kind node {node.get('image')}, "
              f"{node.get('visible_cpus')} CPUs visible, docker limits {node.get('docker_limits')}, cgroup cpu.max `{node.get('cgroup_cpu_max')}`. "
              f"Kubernetes {kn.get('server_version')} on {arch}, {kn.get('node_count')} node.\n")
        reg = registry_latency(out / "registry.txt")
        if reg:
            print(f"**Registry:** {reg}\n")
        print(f"{run.get('restarts')} interleaved rounds per arm after one discarded warm-up round; commit {env.get('git', {}).get('commit')}"
              f"{' (uncommitted changes)' if env.get('git', {}).get('uncommitted_changes') else ''}; run on {run.get('date')}. "
              f"Reproduce with `{run.get('command')}`.\n")

    print("### Validity\n")
    print("| arm | restarts | archive used by the JVM | fetch found an archive |")
    print("|---|---|---|---|")
    for a in ARMS:
        r = by[a]
        print(f"| {a} ({ARM_NOTE[a]}) | {len(r)} | {sum(x['archive_used'] for x in r)} | {sum(x['fetch_hit'] for x in r)} |")
    print()

    print("### Coarse phases (seconds; median and min-max; pod timestamps have 1 s resolution)\n")
    coarse = [
        ("scheduling (created to startTime)", "scheduling"),
        ("install init container", "install_init"),
        ("fetch init container", "fetch_init"),
        ("app container created (initialized to started)", "container_start"),
        ("app started to Ready", "app_to_ready"),
        ("**startTime to Ready**", "total_from_start"),
        ("created to Ready", "total_from_creation"),
    ]
    print("| phase | none | nocache | cached |")
    print("|---|---|---|---|")
    for label, key in coarse:
        print(f"| {label} | " + " | ".join(stat([r[key] for r in by[a]]) for a in ARMS) + " |")
    print()

    print("### Where the tools spend their time (milliseconds)\n")
    fine = [
        ("fetch: whole helper process (JVM start included)", "fetch_process"),
        ("fetch: JDK detection", "fetch_jdk_detect"),
        ("fetch: extract and hash the classpath", "fetch_hash_classpath"),
        ("fetch: store lookup, download and digest check", "fetch_store_get"),
        ("fetch: tool JVM uptime at the end", "fetch_tool_uptime"),
        ("run: whole helper process (JVM start included)", "plan_process"),
        ("run: read manifest", "run_read_manifest"),
        ("run: SHA-256 of the archive", "run_archive_sha"),
        ("run: JDK probe", "run_jdk_probe"),
        ("run: hash the classpath", "run_classpath_hash"),
        ("run: tool JVM uptime at the end", "run_tool_uptime"),
        ("application: JVM-reported start (`Started ... in`)", "jvm_started_in"),
    ]
    print("| step | none | nocache | cached |")
    print("|---|---|---|---|")
    for label, key in fine:
        print(f"| {label} | " + " | ".join(stat([r[key] for r in by[a]], 0) for a in ARMS) + " |")
    print()

    print("### Difference in startTime-to-Ready, per round (seconds)\n")
    print("Each round runs the three arms back to back, so the paired difference cancels slow drift. Median and min-max over rounds.\n")
    rounds = sorted({r["round"] for r in rows})
    def paired(a, b):
        d = []
        for n in rounds:
            x = next((r for r in by[a] if r["round"] == n), None)
            y = next((r for r in by[b] if r["round"] == n), None)
            if x and y and x["total_from_start"] is not None and y["total_from_start"] is not None:
                d.append(y["total_from_start"] - x["total_from_start"])
        return d
    print("| comparison | difference |")
    print("|---|---|")
    print(f"| nocache - none (cost of the machinery, no benefit) | {stat(paired('none', 'nocache'))} |")
    print(f"| cached - none (what a real pod gains or loses) | {stat(paired('none', 'cached'))} |")
    print(f"| cached - nocache (what the archive itself buys) | {stat(paired('nocache', 'cached'))} |")
    print()

    j_none, j_cached = med(by["none"], "jvm_started_in"), med(by["cached"], "jvm_started_in")
    if j_none and j_cached:
        print(f"JVM-reported application start: {j_none:.0f} ms without, {j_cached:.0f} ms with the archive ({j_none - j_cached:+.0f} ms).")
    overhead = [med(by["cached"], k) for k in ("fetch_process", "plan_process")]
    if all(v is not None for v in overhead):
        print(f"The cached arm's helpers (fetch + run) took about {sum(overhead):.0f} ms of wall time before the application started.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: bench_summarize.py <results-dir>")
    main(sys.argv[1])
