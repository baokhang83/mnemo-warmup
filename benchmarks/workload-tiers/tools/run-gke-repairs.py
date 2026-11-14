#!/usr/bin/env python3
"""Rerun only invalid Standard and Larger cells; retain raw data privately."""
import importlib.util
import json
import os
from pathlib import Path

path = Path(__file__).with_name("run-gke-matrix.py")
spec = importlib.util.spec_from_file_location("matrix", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

cfg = m.discover()
cfg["tools"] = os.environ.get("MNEMO_BENCH_TOOLS_IMAGE", cfg["tools"])
manifests = {t: json.loads((m.ART / t / "fetch.manifest.json").read_text()) for t in m.TIERS}
m.RAW = m.ART / "repair-raw"
m.RAW.mkdir(parents=True, exist_ok=True)
out = m.ART / "repair-results.jsonl"
if out.exists():
    raise RuntimeError("repair results already exist; preserve them before restarting")

cells = {
    "standard": [(a, "hpa_1_to_9") for a in m.ARMS],
    "larger": [(a, "hpa_1_to_9") for a in m.ARMS] + [("direct", "single"), ("warm_node_cache", "single")],
}
for tier, trials in cells.items():
    m.refresh_registry_credentials(cfg["registry"])
    m.cache_preflight(tier, cfg, manifests)
    for arm, topology in trials:
        m.refresh_registry_credentials(cfg["registry"])
        print("starting", tier, arm, topology, flush=True)
        try:
            result = m.run_cell(tier, arm, topology, cfg, manifests, 256 if topology == "hpa_1_to_9" else 128)
        except Exception as exc:
            result = {"tier": tier, "arm": arm, "topology": topology,
                      "valid": False, "error_class": type(exc).__name__}
            for resource in ("bench-" + tier, "bench-prime-" + tier):
                m.cmd(["kubectl", "delete", "deployment,service,hpa", resource, "-n", m.NS,
                       "--ignore-not-found", "--wait=true"], timeout=320, check=False)
            m.cmd(["kubectl", "delete", "pod", "benchload-" + tier, "-n", m.NS,
                   "--ignore-not-found", "--wait=false"], timeout=30, check=False)
        with out.open("a") as f:
            f.write(json.dumps(result, separators=(",", ":")) + "\n")
        print("completed", tier, arm, topology, "valid", result.get("valid"), flush=True)
print("repair run complete", flush=True)
