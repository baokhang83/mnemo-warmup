#!/usr/bin/env python3
"""Records the machine and cluster a benchmark ran on, as JSON, so that a result can be compared with another run or checked.

Usage: bench_env.py <kube-context> <kind-node-container> <out.json> [key=value ...]
Everything is read from the live system; extra key=value arguments (app, restarts, archive size...) are stored under "run".
"""
import json
import platform
import re
import subprocess
import sys


def run(*cmd):
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        return out.stdout.strip() if out.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def sh_in(container, script):
    return run("docker", "exec", container, "sh", "-c", script)


def host():
    info = {"system": platform.system(), "kernel": platform.release(), "machine": platform.machine()}
    if platform.system() == "Darwin":
        info["os"] = "macOS " + (run("sw_vers", "-productVersion") or "?")
        info["cpu_model"] = run("sysctl", "-n", "machdep.cpu.brand_string")
        info["logical_cpus"] = int(run("sysctl", "-n", "hw.ncpu") or 0) or None
        info["memory_bytes"] = int(run("sysctl", "-n", "hw.memsize") or 0) or None
    else:
        os_release = {}
        try:
            for line in open("/etc/os-release"):
                k, _, v = line.strip().partition("=")
                os_release[k] = v.strip('"')
        except OSError:
            pass
        info["os"] = os_release.get("PRETTY_NAME")
        cpu = run("sh", "-c", "grep -m1 'model name' /proc/cpuinfo | cut -d: -f2")
        info["cpu_model"] = cpu.strip() if cpu else None
        info["logical_cpus"] = int(run("nproc") or 0) or None
        mem = run("sh", "-c", "awk '/MemTotal/ {print $2*1024}' /proc/meminfo")
        info["memory_bytes"] = int(mem) if mem else None
    return info


def docker_info():
    raw = run("docker", "info", "--format", "{{json .}}")
    if not raw:
        return {}
    d = json.loads(raw)
    return {
        "server_version": d.get("ServerVersion"),
        "operating_system": d.get("OperatingSystem"),
        "kernel": d.get("KernelVersion"),
        "architecture": d.get("Architecture"),
        "cpus_available_to_docker": d.get("NCPU"),
        "memory_available_to_docker_bytes": d.get("MemTotal"),
        "cgroup_version": d.get("CgroupVersion"),
        "storage_driver": d.get("Driver"),
    }


def kind_node(container):
    inspect = run("docker", "inspect", container)
    node = {}
    if inspect:
        i = json.loads(inspect)[0]
        hc = i.get("HostConfig", {})
        node["image"] = i.get("Config", {}).get("Image")
        # 0 means "no limit" for all of these
        node["docker_limits"] = {
            "nano_cpus": hc.get("NanoCpus"),
            "cpu_quota": hc.get("CpuQuota"),
            "cpu_shares": hc.get("CpuShares"),
            "cpuset_cpus": hc.get("CpusetCpus"),
            "memory_bytes": hc.get("Memory"),
        }
    node["cgroup_cpu_max"] = sh_in(container, "cat /sys/fs/cgroup/cpu.max 2>/dev/null || cat /sys/fs/cgroup/cpu/cpu.cfs_quota_us 2>/dev/null")
    node["cgroup_memory_max"] = sh_in(container, "cat /sys/fs/cgroup/memory.max 2>/dev/null || cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null")
    node["visible_cpus"] = int(sh_in(container, "nproc") or 0) or None
    return node


def kubernetes(ctx):
    ver = run("kubectl", "--context", ctx, "version", "-o", "json")
    nodes = run("kubectl", "--context", ctx, "get", "nodes", "-o", "json")
    out = {}
    if ver:
        out["server_version"] = json.loads(ver).get("serverVersion", {}).get("gitVersion")
    if nodes:
        n = json.loads(nodes)["items"][0]
        out["node_info"] = n["status"]["nodeInfo"]
        out["node_capacity"] = n["status"]["capacity"]
        out["node_allocatable"] = n["status"]["allocatable"]
        out["node_count"] = len(json.loads(nodes)["items"])
    return out


def tool_versions():
    v = {
        "java": run("sh", "-c", "java -version 2>&1 | head -1"),
        "helm": run("helm", "version", "--short"),
        "kubectl": run("kubectl", "version", "--client", "-o", "json"),
        "kind": run("kind", "version"),
        "python": platform.python_version(),
    }
    if v["kubectl"]:
        v["kubectl"] = json.loads(v["kubectl"]).get("clientVersion", {}).get("gitVersion")
    return v


def main():
    ctx, node, out = sys.argv[1:4]
    extra = dict(a.split("=", 1) for a in sys.argv[4:] if "=" in a)
    git = run("git", "rev-parse", "--short", "HEAD")
    dirty = bool(run("git", "status", "--porcelain"))
    doc = {
        "run": extra,
        "git": {"commit": git, "uncommitted_changes": dirty},
        "host": host(),
        "docker": docker_info(),
        "kind_node": kind_node(node),
        "kubernetes": kubernetes(ctx),
        "tools": tool_versions(),
        "registry": {"implementation": "registry:2 (CNCF distribution) in a container on the kind Docker network, plain HTTP",
                     "note": "latency is measured from the kind node: see registry.txt"},
    }
    with open(out, "w") as f:
        json.dump(doc, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
