#!/usr/bin/env python3
"""Produce a sanitized aggregate from ignored original and repaired records."""
import json
import pathlib
import statistics

root = pathlib.Path(__file__).resolve().parents[3]
art = root / "benchmarks/workload-tiers/artifacts/20261114-gke-amd64"

def rows(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]

original = rows(art / "matrix-results.jsonl")
repairs = rows(art / "repair-results.jsonl")
key = lambda r: (r["tier"], r["topology"], r["arm"])
combined = {key(r): r for r in original}
for r in repairs:
    combined[key(r)] = r
if len(combined) != 32 or len(repairs) != 10:
    raise RuntimeError("expected 32 distinct cells and 10 repairs")

tiers = ("minimal", "standard", "larger", "thingsboard")
arms = ("uncached", "direct", "cold_node_cache", "warm_node_cache")
topologies = ("single", "hpa_1_to_9")
def sec(v):
    return "—" if v is None else f"{v / 1000:.1f}"
def med(v):
    values = [x for x in v if x > 0]
    return sec(statistics.median(values)) if values else "—"
def val(v):
    return "—" if v is None else str(v)

out = ["# GKE workload-tier benchmark: repaired cells", "",
       "One trial per cell; values are descriptive. A fixed one-node cluster was used. "
       "Standard and Larger HPA repairs used 256 load workers with a two-CPU load-generator limit; "
       "the original cells used 128 workers with a 500m limit. Single-pod runs used 128 workers.", "",
       "The original HPA timings use watch receipt times and may include watch buffering; repaired "
       "HPA timings use direct polling. Original pod watches lack a recorded trial-start boundary; "
       "zero durations are excluded but other stale observations cannot be ruled out. "
       "These limitations prevent a precise comparison of HPA reaction across original and repaired runs.", "",
       "App Ready and fetch/hash are medians of observed per-pod durations in seconds. HPA reaction is "
       "load start to first scale request; HPA desired 9 is load start to requesting nine pods; "
       "HPA Ready is load start to nine Ready pods. "
       "RPS is the mean of up to the final 20 one-second samples. Validity covers startup, cache, "
       "scale target and watch checks; request failures are listed independently. RX/TX are "
       "workload-namespace traffic deltas, not node-wide network traffic.", "",
       "| Tier | Topology | Arm | Valid | Pods | App Ready s | App observations | Fetch/hash s | HPA reaction s | HPA desired 9 s | HPA Ready s | Hits/misses | RPS | Failures | RX MiB | TX MiB |",
       "|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
for tier in tiers:
    for topology in topologies:
        for arm in arms:
            r = combined[(tier, topology, arm)]
            load = r.get("load", {}); hpa = r.get("hpa", {})
            rx = r.get("workload_namespace_rx_bytes"); tx = r.get("workload_namespace_tx_bytes")
            size = lambda x: "—" if x is None else f"{x / 1048576:.1f}"
            observations = sum(x > 0 for x in r.get("app_start_to_ready_ms", []))
            out.append("| " + " | ".join([tier, topology, arm, "yes" if r.get("valid") else "no", val(r.get("pod_count")), med(r.get("app_start_to_ready_ms", [])), str(observations), med(r.get("fetch_hash_ms", [])), sec(hpa.get("first_scale_ms")), sec(hpa.get("desired_9_ms")), sec(hpa.get("all_9_ready_ms")), f"{val(r.get('cache_hits'))}/{val(r.get('cache_misses'))}", val(load.get("rps_mean")), val(load.get("failures_total")), size(rx), size(tx)]) + " |")
out += ["", "The original minimal direct HPA and cold-cache single-pod cells each recorded ten connection-refused errors at load start, before sustained successful traffic. "
        "The direct HPA errors preceded the first observed scale action. The logs do not identify the rejecting network component; "
        "the load generator checks for connection refusal before DNS errors, so a wrapped DNS refusal could be counted in that class.", "",
        "The original Larger direct-fetch and warm-cache primer failures included registry HTTP 401 responses; repaired cells refresh credentials before each trial.", "",
        "ThingsBoard warm-cache HPA's original watch-reported 238.2 seconds includes an unusually late first watch observation. "
        "Available logs do not establish whether the delay arose in HPA, watch delivery, CPU, or the database.", ""]
out += ["## Archive sizes", "", "| Tier | Archive bytes | SHA-256 |", "|---|---:|---|"]
for tier in tiers:
    r = next(r for r in combined.values() if r["tier"] == tier and r.get("archive_bytes") is not None)
    digest = r["archive_sha256"]
    if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
        raise ValueError("invalid archive digest")
    out.append(f"| {tier} | {int(r['archive_bytes'])} | {digest} |")
out += ["", "## Request failure classes", "", "| Tier | Topology | Arm | Class | Count |", "|---|---|---|---|---:|"]
allowed = {"network_timeout", "connection_refused", "connection_reset", "dns_error", "network_other", "response_body_error"}
for tier in tiers:
    for topology in topologies:
        for arm in arms:
            classes = combined[(tier, topology, arm)].get("load", {}).get("failure_classes", {})
            for name, count in sorted(classes.items()):
                if name not in allowed and not (name.startswith("http_") and name[5:].isdigit()):
                    raise ValueError("unexpected failure class")
                out.append(f"| {tier} | {topology} | {arm} | {name} | {int(count)} |")
out.append("")
target = root / "benchmarks/workload-tiers/results/gke-repaired.md"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text("\n".join(out))
print("Sanitized aggregate written")
