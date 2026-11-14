#!/usr/bin/env python3
"""Run the one-round GKE workload-tier matrix. Raw run data stays in ignored artifacts/."""
from __future__ import annotations
import base64, copy, datetime as dt, hashlib, json, math, os, pathlib, re, statistics, subprocess, threading, time, urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[3]
ART = ROOT / "benchmarks/workload-tiers/artifacts/20261114-gke-amd64"
RAW = ART / "matrix-raw"
NS = "workload-study"
TIERS = ("minimal", "standard", "larger", "thingsboard")
ARMS = ("uncached", "direct", "cold_node_cache", "warm_node_cache")
TOPOLOGIES = ("single", "hpa_1_to_9")
LOAD_WORKERS = {t: 128 for t in TIERS}
HPA_TIMEOUT = 300


def cmd(args, inp=None, timeout=60, check=True):
    p = subprocess.run(args, input=inp, text=True, capture_output=True, timeout=timeout)
    if check and p.returncode:
        raise RuntimeError("command failed: " + " ".join(args[:2]))
    return p


def kubectl(*args, inp=None, timeout=60):
    return cmd(["kubectl", *args], inp, timeout)


def get_json(*args):
    for _ in range(7):
        try:
            return json.loads(kubectl(*args, "-o", "json", timeout=25).stdout)
        except Exception:
            time.sleep(1)
    raise RuntimeError("Kubernetes API query failed repeatedly")


def apply_obj(obj):
    kubectl("apply", "-f", "-", inp=json.dumps(obj), timeout=90)


def wait_ready(deployment, wanted=1, timeout=420):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        x = get_json("get", "deployment", deployment, "-n", NS).get("status", {})
        if x.get("readyReplicas", 0) >= wanted and x.get("updatedReplicas", 0) >= wanted:
            return
        time.sleep(1)
    raise RuntimeError("workload readiness timeout")


def refresh_registry_credentials(registry):
    token = cmd(["gcloud", "auth", "print-access-token"], timeout=30).stdout.strip()
    if not token:
        raise RuntimeError("registry credential refresh returned empty token")
    sec = get_json("get", "secret", "ar-registry", "-n", NS)
    docker = json.loads(base64.b64decode(sec["data"][".dockerconfigjson"]))
    auth = base64.b64encode(("oauth2accesstoken:" + token).encode()).decode()
    registry_host = registry.split("/", 1)[0]
    docker.setdefault("auths", {}).pop(registry, None)
    docker["auths"][registry_host] = {"username": "oauth2accesstoken", "password": token, "auth": auth}
    sec["data"][".dockerconfigjson"] = base64.b64encode(json.dumps(docker, separators=(",", ":")).encode()).decode()
    sec["metadata"] = {"name": "ar-registry", "namespace": NS}
    apply_obj(sec)
    sec = get_json("get", "secret", "nodecache-config", "-n", NS)
    sec["data"]["source.auth"] = base64.b64encode(("Basic " + auth).encode()).decode()
    # This Secret also carries the public CA bundle; client-side apply would
    # duplicate its large payload in last-applied-configuration annotations.
    sec.get("metadata", {}).get("annotations", {}).pop("kubectl.kubernetes.io/last-applied-configuration", None)
    kubectl("replace", "-f", "-", inp=json.dumps(sec), timeout=90)
    time.sleep(65)  # Allow projected Secret volumes to pick up the new OAuth token.


def discover():
    project = cmd(["gcloud", "config", "get-value", "project", "--quiet"], timeout=20).stdout.strip()
    if not project or project == "(unset)":
        raise RuntimeError("active Cloud project is not configured")
    repos = json.loads(cmd(["gcloud", "artifacts", "repositories", "list", "--project", project, "--location=europe-west3", "--format=json"]).stdout)
    repo = next(x for x in repos if "mnemo" in x.get("name", "").lower())
    registry = "europe-west3-docker.pkg.dev/" + project + "/" + repo["name"].split("/")[-1]
    images = json.loads(cmd(["gcloud", "artifacts", "docker", "images", "list", registry, "--include-tags", "--format=json"]).stdout)
    def image(tier):
        entry = next(x for x in images if x.get("package", "").rsplit("/", 1)[-1] == tier and "study72" in x.get("tags", []))
        return registry + "/" + entry["package"].split("/")[-1] + "@" + entry["version"]
    current = cmd(["kubectl", "get", "deployment", "bench-thingsboard", "-n", NS, "-o", "json"], check=False)
    if current.returncode == 0:
        tb = json.loads(current.stdout)
    else:
        # The matrix deletes its trial Deployment. Recover its original injected
        # template from the private, ignored smoke configuration.
        saved = cmd(["kubectl", "create", "--dry-run=client", "-f", str(ART / "smoke-thingsboard-injected.yaml"), "-o", "json"]).stdout.lstrip()
        tb, _ = json.JSONDecoder().raw_decode(saved)
        if tb.get("kind") != "Deployment":
            raise RuntimeError("saved ThingsBoard deployment is unavailable")
    anns = tb["spec"]["template"]["metadata"].get("annotations", {})
    spec = tb["spec"]["template"]["spec"]
    tools = next(c["image"] for c in spec.get("initContainers", []) if c.get("name") == "mnemo-warmup-install")
    cfg = {
        "registry": registry, "tools": tools,
        "pull_secret": anns.get("warmup.mnemo.io/pull-secret", "ar-registry"),
        "cache": {k: anns[k] for k in ("warmup.mnemo.io/node-cache-url", "warmup.mnemo.io/node-cache-ca-configmap", "warmup.mnemo.io/node-cache-audience")},
        "service_account": spec.get("serviceAccountName", "bench-runner"),
        "thingsboard_store": anns["warmup.mnemo.io/store"],
        "thingsboard_deployment": tb,
        "images": {t: image(t) for t in ("minimal", "standard", "larger")},
    }
    cfg["images"]["thingsboard"] = tb["spec"]["template"]["spec"]["containers"][0]["image"]
    return cfg


def clean_tb_base(deployment):
    d = copy.deepcopy(deployment)
    d["metadata"] = {"name": "bench-thingsboard", "namespace": NS, "labels": {"app": "bench-thingsboard"}}
    d.pop("status", None)
    d["spec"]["replicas"] = 1
    d["spec"]["strategy"] = {"type": "Recreate"}
    d["spec"]["selector"] = {"matchLabels": {"app": "bench-thingsboard"}}
    tm = d["spec"]["template"]["metadata"]
    tm["labels"] = {"app": "bench-thingsboard"}
    tm["annotations"] = {}
    ps = d["spec"]["template"]["spec"]
    ps.pop("initContainers", None)
    removed = {v["name"] for v in ps.get("volumes", []) if v.get("name", "").startswith("mnemo-")}
    ps["volumes"] = [v for v in ps.get("volumes", []) if v.get("name") not in removed]
    c = ps["containers"][0]
    c["volumeMounts"] = [m for m in c.get("volumeMounts", []) if m.get("name") not in removed]
    original = c.get("args", [])
    if original[:2] == ["exec", "--"] and len(original) >= 3:
        c["command"], c["args"] = [original[2]], original[3:]
    c["env"] = [e for e in c.get("env", []) if not e.get("name", "").startswith("MNEMO_WARMUP_")]
    c["env"] = [e for e in c["env"] if e.get("name") != "MNEMO_WARMUP_TIMING"]
    c["env"].append({"name": "MNEMO_WARMUP_TIMING", "value": "1"})
    return d


def app_base(tier, name, cfg, manifests):
    if tier == "thingsboard":
        d = clean_tb_base(cfg["thingsboard_deployment"])
        d["metadata"]["name"] = name
        d["spec"]["selector"]["matchLabels"] = {"app": name}
        d["spec"]["template"]["metadata"]["labels"] = {"app": name}
        return d
    name_label = name
    flags = manifests[tier].get("jvmFlags", [])
    env = [{"name": "MNEMO_WARMUP_TIMING", "value": "1"}]
    resources = {"requests": {"cpu": "500m", "memory": "1Gi"}, "limits": {"cpu": "2", "memory": "1536Mi"}}
    if tier in ("standard", "larger"):
        env += [
            {"name": "BENCH_DB_URL", "value": "jdbc:postgresql://postgres.workload-study.svc.cluster.local:5432/bench"},
            {"name": "BENCH_DB_USER", "valueFrom": {"secretKeyRef": {"name": "db-auth", "key": "username"}}},
            {"name": "BENCH_DB_PASSWORD", "valueFrom": {"secretKeyRef": {"name": "db-auth", "key": "password"}}},
        ]
        resources = {"requests": {"cpu": "1", "memory": "2Gi"}, "limits": {"cpu": "2", "memory": "3Gi"}}
    labels = {"app": name_label}
    return {"apiVersion": "apps/v1", "kind": "Deployment", "metadata": {"name": name, "namespace": NS, "labels": labels}, "spec": {
        "replicas": 1, "strategy": {"type": "Recreate"}, "selector": {"matchLabels": labels},
        "template": {"metadata": {"labels": labels, "annotations": {}}, "spec": {"serviceAccountName": cfg["service_account"], "containers": [{
            "name": "app", "image": cfg["images"][tier], "imagePullPolicy": "IfNotPresent", "command": ["java"],
            "args": flags + ["-jar", "/app/app.jar"], "env": env, "resources": resources, "ports": [{"containerPort": 8080}],
            "readinessProbe": {"httpGet": {"path": "/actuator/health/readiness", "port": 8080}, "periodSeconds": 1, "timeoutSeconds": 2, "failureThreshold": 120}
        }]}}}}


def annotate_and_inject(d, tier, arm, cfg):
    if arm == "uncached":
        return d
    ann = d["spec"]["template"]["metadata"].setdefault("annotations", {})
    store = cfg["thingsboard_store"] if tier == "thingsboard" else "oci://" + cfg["registry"] + "/aot-" + tier
    ann.update({"warmup.mnemo.io/enabled": "true", "warmup.mnemo.io/store": store, "warmup.mnemo.io/exit-mode": "spring-refresh", "warmup.mnemo.io/pull-secret": cfg["pull_secret"]})
    if arm in ("cold_node_cache", "warm_node_cache"):
        ann.update(cfg["cache"])
    p = cmd(["java", "-jar", str(ROOT / "warmup-cli/target/mnemo-warmup.jar"), "inject", "--tools-image=" + cfg["tools"]], json.dumps(d), timeout=90)
    return p.stdout


def hpa_obj(name, topology):
    mn, mx = (1, 1) if topology == "single" else (1, 9)
    return {"apiVersion": "autoscaling/v2", "kind": "HorizontalPodAutoscaler", "metadata": {"name": name, "namespace": NS}, "spec": {
        "scaleTargetRef": {"apiVersion": "apps/v1", "kind": "Deployment", "name": name}, "minReplicas": mn, "maxReplicas": mx,
        "metrics": [{"type": "Resource", "resource": {"name": "cpu", "target": {"type": "Utilization", "averageUtilization": 60}}}],
        "behavior": {"scaleUp": {"stabilizationWindowSeconds": 0, "selectPolicy": "Max", "policies": [{"type": "Pods", "value": 9, "periodSeconds": 15}]}, "scaleDown": {"stabilizationWindowSeconds": 300, "selectPolicy": "Min", "policies": [{"type": "Pods", "value": 1, "periodSeconds": 60}]}}
    }}


def service_obj(name):
    return {"apiVersion": "v1", "kind": "Service", "metadata": {"name": name, "namespace": NS}, "spec": {"selector": {"app": name}, "ports": [{"port": 8080, "targetPort": 8080}]}}


class WatchSet:
    def __init__(self, name):
        self.name = name; self.stop_event = threading.Event(); self.events = []; self.errors = []; self.procs = []; self.threads = []; self.lock = threading.Lock()
    def start_one(self, kind, args, cols):
        p = subprocess.Popen(["kubectl", "get", kind, *args, "-w", "--no-headers", "-o", "custom-columns=" + cols], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
        self.procs.append(p)
        def reader():
            try:
                while not self.stop_event.is_set():
                    line = p.stdout.readline()
                    if not line: break
                    with self.lock: self.events.append({"mono_ns": time.monotonic_ns(), "utc": dt.datetime.now(dt.timezone.utc).isoformat(), "kind": kind, "state": line.strip()})
            finally:
                if not self.stop_event.is_set() and p.poll() is not None and p.returncode not in (0, None): self.errors.append(kind)
        t = threading.Thread(target=reader, daemon=True); t.start(); self.threads.append(t)
    def start(self):
        self.start_one("pods", ["-n", NS, "-l", "app=" + self.name], 'UID:.metadata.uid,PHASE:.status.phase,READY:.status.conditions[?(@.type=="Ready")].status,STARTED:.status.containerStatuses[0].state.running.startedAt,CREATED:.metadata.creationTimestamp,SCHEDULED:.status.conditions[?(@.type=="PodScheduled")].lastTransitionTime,LASTREADY:.status.conditions[?(@.type=="Ready")].lastTransitionTime')
        self.start_one("deployments", ["-n", NS], 'NAME:.metadata.name,REPLICAS:.status.replicas,READY:.status.readyReplicas')
        self.start_one("hpa", ["-n", NS], 'NAME:.metadata.name,CURRENT:.status.currentReplicas,DESIRED:.status.desiredReplicas')
        time.sleep(2)
    def close(self):
        self.stop_event.set()
        for p in self.procs:
            if p.poll() is None: p.terminate()
        for t in self.threads:t.join(timeout=2)
        return self.events


def measure_from_watch(events, trial_start=None, demand_start_ns=None):
    started={}; ready={}; hpa=[]
    for e in events:
        s=e["state"].split()
        if e["kind"]=="pods" and len(s)>=5:
            uid,phase,rdy,st=s[:4]
            if uid.startswith("<") or uid=="UID":continue
            if trial_start is not None:
                try:
                    created=dt.datetime.fromisoformat(s[4].replace("Z", "+00:00"))
                except ValueError:
                    continue
                if created < trial_start:continue
            if st not in ("<none>","None","-") and uid not in started:started[uid]=e["mono_ns"]
            if rdy=="True" and uid not in ready:ready[uid]=e["mono_ns"]
        elif e["kind"]=="hpa" and len(s)>=3 and (demand_start_ns is None or e["mono_ns"] >= demand_start_ns):
            try:hpa.append((e["mono_ns"],s[0],int(s[1]),int(s[2])))
            except:pass
    durs=[round((ready[u]-started[u])/1e6,1) for u in ready if u in started and ready[u]>started[u]]
    return started,ready,durs,hpa


def get_pods(selector):
    return get_json("get", "pods", "-n", NS, "-l", selector).get("items", [])


def ready_count(name):
    return sum(any(c.get("type")=="Ready" and c.get("status")=="True" for c in p.get("status",{}).get("conditions",[])) for p in get_pods("app="+name))


def nodecache_daemonset():
    ds=get_json("get","daemonsets","-n",NS).get("items",[])
    return next(d for d in ds if any("nodecache" in c.get("name","") or "mnemo-nodecache" in c.get("image","") for c in d["spec"]["template"]["spec"].get("containers",[])))


def clear_nodecache(cfg):
    d=nodecache_daemonset();name=d["metadata"]["name"]
    kubectl("rollout","restart","daemonset",name,"-n",NS,timeout=60)
    kubectl("rollout","status","daemonset",name,"-n",NS,"--timeout=240s",timeout=260)
    endpoint = urllib.parse.urlparse(cfg["cache"]["warmup.mnemo.io/node-cache-url"])
    probe = "bench-cache-connectivity"
    pod = {"apiVersion":"v1", "kind":"Pod", "metadata":{"name":probe,"namespace":NS}, "spec":{"restartPolicy":"Never", "imagePullSecrets":[{"name":cfg["pull_secret"]}], "containers":[{"name":"probe","image":cfg["tools"],"command":["sh","-c","sleep 180"]}]}}
    try:
        apply_obj(pod)
        kubectl("wait", "-n", NS, "--for=condition=Ready", "pod/"+probe, "--timeout=90s", timeout=100)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            p = cmd(["kubectl", "exec", "-n", NS, probe, "--", "nc", "-z", "-w", "3", endpoint.hostname, str(endpoint.port or 443)], timeout=10, check=False)
            if p.returncode == 0:
                return
            time.sleep(2)
        raise RuntimeError("node-cache TCP connectivity preflight failed")
    finally:
        cmd(["kubectl", "delete", "pod", probe, "-n", NS, "--ignore-not-found", "--wait=true"], timeout=90, check=False)


def metric_snapshot():
    nodes=get_json("get","nodes").get("items",[]);node=nodes[0]["metadata"]["name"]
    raw=kubectl("get","--raw",f"/api/v1/nodes/{node}/proxy/metrics/cadvisor",timeout=35).stdout
    out={}
    pat=re.compile(r'^container_network_(receive|transmit)_bytes_total\{([^}]*)\}\s+([0-9.eE+-]+)(?:\s+\d+)?$')
    for line in raw.splitlines():
        m=pat.match(line)
        if not m:continue
        labels=dict(re.findall(r'([a-zA-Z_]+)="([^"]*)"',m.group(2)))
        if labels.get("namespace")!=NS:continue
        pod=labels.get("pod"); iface=labels.get("interface"); container=labels.get("container","")
        if not pod or iface=="lo":
            continue
        key=(pod,iface,container,m.group(1));out[key]=float(m.group(3))
    return out


def metric_delta(a,b):
    rx=tx=0
    for k,v in b.items():
        old=a.get(k,0);delta=v-old if v>=old else v
        if k[-1]=="receive":rx+=max(0,delta)
        else:tx+=max(0,delta)
    return int(rx),int(tx)


def loadgen_start(tier,name,cfg,workers):
    pod="benchload-"+tier
    try:kubectl("delete","pod",pod,"-n",NS,"--ignore-not-found","--wait=true",timeout=60)
    except:pass
    path="/login" if tier=="thingsboard" else ("/customers" if tier in ("standard","larger") else "/actuator/health/readiness")
    service="bench-"+tier
    load_cpu = "2" if tier in ("standard", "larger") and workers > 128 else "500m"
    obj={"apiVersion":"v1","kind":"Pod","metadata":{"name":pod,"namespace":NS},"spec":{"restartPolicy":"Never","containers":[{"name":"loadgen","image":cfg["tools"],"command":["/opt/mnemo-warmup/bin/mnemo-benchload","--url",f"http://{service}.{NS}.svc.cluster.local:8080{path}","--workers",str(workers)],"resources":{"requests":{"cpu":"100m","memory":"64Mi"},"limits":{"cpu":load_cpu,"memory":"256Mi"}},"volumeMounts":[{"name":"gate","mountPath":"/run/mnemo-benchload"}]}],"volumes":[{"name":"gate","emptyDir":{}}]}}
    apply_obj(obj)
    for _ in range(90):
        try:
            p=get_json("get","pod",pod,"-n",NS)
            if p.get("status",{}).get("phase")=="Running":return pod
        except:pass
        time.sleep(1)
    raise RuntimeError("load generator did not start")


def loadgen_gate(pod,enabled):
    op="touch" if enabled else "rm -f"
    kubectl("exec","-n",NS,pod,"--","sh","-c",op+" /run/mnemo-benchload/enabled",timeout=30)


def wait_service_endpoint(name, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        obj = get_json("get", "endpoints", name, "-n", NS)
        if any(subset.get("addresses") for subset in obj.get("subsets", [])):
            return
        time.sleep(1)
    raise RuntimeError("service has no ready endpoint")


def parse_load(log):
    rows=[]; classes={}
    for l in log.splitlines():
        m=re.search(r'requests_per_second=(\d+) failures_per_second=(\d+) totals=(\d+)/(\d+)',l)
        if m:rows.append(tuple(map(int,m.groups())))
        c=re.search(r'failure_class=([a-z0-9_]+) total=(\d+)',l)
        if c:classes[c.group(1)]=int(c.group(2))
    rates=[r[0] for r in rows[-20:]]
    return {"requests_total":rows[-1][2] if rows else 0,"failures_total":rows[-1][3] if rows else 0,"failure_classes":classes,"rps_mean":round(statistics.mean(rates),1) if rates else None,"rps_min":min(rates) if rates else None,"rps_max":max(rates) if rates else None,"seconds_sampled":len(rows)}


def logs_and_counts(tier,name,rawdir):
    pods=get_pods("app="+name);times=[];bytes_seen=[];hits=misses=0;bad=[];restarts=0
    (rawdir/"pod-count.txt").write_text(str(len(pods)))
    for i,p in enumerate(pods):
        rest=sum(x.get("restartCount",0) for x in p.get("status",{}).get("containerStatuses",[]));restarts+=rest
        for c in p.get("spec",{}).get("initContainers",[])+p.get("spec",{}).get("containers",[]):
            q=cmd(["kubectl","logs","-n",NS,p["metadata"]["name"],"-c",c["name"],"--tail=-1"],timeout=35,check=False)
            s=q.stdout
            (rawdir/f"pod-{i+1}-{c['name']}.log").write_text(s)
            for m in re.finditer(r'archive-transfer-hash-write=(\d+) ms, archive-bytes=(\d+)',s):times.append(int(m.group(1)));bytes_seen.append(int(m.group(2)))
            hits+=len(re.findall(r'node cache hit: verified archive',s));misses+=len(re.findall(r'node cache miss: verified archive',s))
            if q.returncode or re.search(r'(?i)(no archive stored|no usable archive|registry auth|failed to fetch|fallback to registry|node cache unavailable or invalid|node cache configuration unavailable)',s):bad.append(1)
    return {"pod_count":len(pods),"pod_restarts":restarts,"fetch_hash_ms":times,"fetch_bytes_observed":bytes_seen,"cache_hits":hits,"cache_misses":misses,"fetch_errors":sum(bad)}


def wait_load_window(pod,seconds):
    loadgen_gate(pod,True);time.sleep(seconds);loadgen_gate(pod,False)
    p=cmd(["kubectl","logs","-n",NS,pod,"-c","loadgen","--tail=-1"],timeout=35,check=False)
    return p.stdout


def deploy_apply(tier,name,arm,cfg,manifests,base=None):
    d=base if base is not None else app_base(tier,name,cfg,manifests)
    d=copy.deepcopy(d);d["metadata"]["name"]=name;d["spec"]["selector"]["matchLabels"]={"app":name};d["spec"]["replicas"]=1;d["spec"]["strategy"]={"type":"Recreate"};d["spec"]["template"]["metadata"]["labels"]={"app":name}
    d=annotate_and_inject(d,tier,arm,cfg)
    if isinstance(d,str):
        apply_input=d
        p=cmd(["kubectl","apply","-f","-"],apply_input,timeout=90)
    else:apply_obj(d)


def cache_preflight(tier, cfg, manifests):
    """Verify a fresh cache serves a miss and then a hit before measuring."""
    name = "bench-prime-" + tier
    rawdir = RAW / tier / ("preflight-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
    clear_nodecache(cfg)
    try:
        for expected in ("miss", "hit"):
            deploy_apply(tier, name, "warm_node_cache", cfg, manifests)
            wait_ready(name, 1, 420)
            out = rawdir / expected
            out.mkdir(parents=True, exist_ok=True)
            counts = logs_and_counts(tier, name, out)
            if counts["fetch_errors"] or counts["cache_" + expected + "es" if expected == "miss" else "cache_hits"] != 1:
                raise RuntimeError("cache preflight did not verify " + expected)
            kubectl("delete", "deployment", name, "-n", NS, "--wait=true", "--timeout=300s", timeout=320)
    finally:
        cmd(["kubectl", "delete", "deployment", name, "-n", NS, "--ignore-not-found", "--wait=true"], timeout=90, check=False)


def run_cell(tier,arm,topology,cfg,manifests,workers):
    name="bench-"+tier; rawdir=RAW/tier/arm/topology;rawdir.mkdir(parents=True,exist_ok=True)
    # Cache priming and reset are deliberately outside measured intervals.
    if arm in ("cold_node_cache","warm_node_cache"):
        clear_nodecache(cfg)
    if arm=="warm_node_cache":
        prime="bench-prime-"+tier
        primed=app_base(tier,prime,cfg,manifests)
        deploy_apply(tier,prime,arm,cfg,manifests,primed)
        wait_ready(prime,1,420)
        primeraw=rawdir/"prime";primeraw.mkdir(parents=True,exist_ok=True)
        prime_logs=logs_and_counts(tier,prime,primeraw)
        if prime_logs["fetch_errors"] or prime_logs["cache_misses"]<1:
            raise RuntimeError("warm-cache priming did not verify a cold miss")
        kubectl("delete","deployment",prime,"-n",NS,"--wait=true","--timeout=300s",timeout=320)
    # Delete stale trial objects and wait before establishing the next round.
    cmd(["kubectl","delete","deployment,service,hpa",name,"-n",NS,"--ignore-not-found","--wait=true","--timeout=300s"],timeout=320)
    apply_obj(service_obj(name))
    watches=WatchSet(name);watches.start()
    apply_obj(hpa_obj(name,topology))
    metric_before=metric_snapshot()
    start=time.monotonic_ns(); trial_start=dt.datetime.now(dt.timezone.utc); start_utc=trial_start.isoformat()
    # Kubernetes creationTimestamp has second precision; ensure a new pod's
    # timestamp cannot round down before the strict trial boundary.
    while dt.datetime.now(dt.timezone.utc).replace(microsecond=0) <= trial_start:
        time.sleep(0.05)
    deploy_apply(tier,name,arm,cfg,manifests)
    wait_ready(name,1,420)
    wait_service_endpoint(name)
    loadpod=loadgen_start(tier,name,cfg,workers)
    if topology=="single":
        demand=time.monotonic_ns();log=wait_load_window(loadpod,15);demand_utc=dt.datetime.now(dt.timezone.utc).isoformat()
        hpa_times={};desired=1;current=1
    else:
        demand=time.monotonic_ns();demand_utc=dt.datetime.now(dt.timezone.utc).isoformat();loadgen_gate(loadpod,True)
        deadline=time.monotonic()+HPA_TIMEOUT;desired=current=ready=0;desired9=ready9=current9=None;first_scale=None;settle=None
        while time.monotonic()<deadline:
            h=get_json("get","hpa",name,"-n",NS)["status"];d=get_json("get","deployment",name,"-n",NS)["status"]
            desired=h.get("desiredReplicas",0);current=h.get("currentReplicas",0);ready=d.get("readyReplicas",0);now=time.monotonic_ns()
            if desired>1 and first_scale is None:first_scale=now
            if desired>=9 and desired9 is None:desired9=now
            if current>=9 and current9 is None:current9=now
            if ready>=9 and ready9 is None:ready9=now;settle=time.monotonic()+20
            if settle and time.monotonic()>=settle:break
            time.sleep(1)
        loadgen_gate(loadpod,False)
        log=cmd(["kubectl","logs","-n",NS,loadpod,"-c","loadgen","--tail=-1"],timeout=35,check=False).stdout
        hpa_times={"first_scale_ms":round((first_scale-demand)/1e6,1) if first_scale else None,"desired_9_ms":round((desired9-demand)/1e6,1) if desired9 else None,"current_9_ms":round((current9-demand)/1e6,1) if current9 else None,"all_9_ready_ms":round((ready9-demand)/1e6,1) if ready9 else None}
    (rawdir/"loadgen.log").write_text(log)
    details=logs_and_counts(tier,name,rawdir)
    events=watches.close()
    with (rawdir/"watch.jsonl").open("w") as f:
        f.write(json.dumps({"event":"trial_start","mono_ns":start,"utc":start_utc})+"\n")
        f.write(json.dumps({"event":"demand_start","mono_ns":demand,"utc":demand_utc})+"\n")
        for e in events:f.write(json.dumps(e)+"\n")
    started,ready_obs,appdur,hpa_obs=measure_from_watch(events,trial_start,demand)
    hpa_watch_times={}
    if topology=="hpa_1_to_9":
        hpa_rows=[x for x in hpa_obs if x[1]==name]
        def first_at(predicate):
            return next((x[0] for x in hpa_rows if predicate(x[2],x[3])),None)
        first_scale=first_at(lambda cur,des:des>1)
        desired9=first_at(lambda cur,des:des>=9)
        current9=first_at(lambda cur,des:cur>=9)
        allready=max(ready_obs.values()) if len(ready_obs)>=9 else None
        hpa_watch_times={"first_scale_ms":round((first_scale-demand)/1e6,1) if first_scale else None,"desired_9_ms":round((desired9-demand)/1e6,1) if desired9 else None,"current_9_ms":round((current9-demand)/1e6,1) if current9 else None,"all_9_ready_ms":round((allready-demand)/1e6,1) if allready else None}
    net_after=metric_snapshot();rx,tx=metric_delta(metric_before,net_after)
    lres=parse_load(log)
    cache_ok=(arm not in ("cold_node_cache","warm_node_cache") or details["cache_hits"]+details["cache_misses"]>=details["pod_count"])
    if arm=="cold_node_cache":cache_ok=cache_ok and details["cache_misses"]>=1
    if arm=="warm_node_cache":cache_ok=cache_ok and details["cache_hits"]>=details["pod_count"]
    timing_ok=arm=="uncached" or len(details["fetch_hash_ms"])>=details["pod_count"]
    request_cpu="500m" if tier=="minimal" else "1"
    scale_ok=topology=="single" or (hpa_times["all_9_ready_ms"] is not None and desired==9 and current==9 and ready==9)
    valid=timing_ok and cache_ok and details["fetch_errors"]==0 and scale_ok and not watches.errors
    result={"tier":tier,"arm":arm,"topology":topology,"round":1,"node_count":1,"workers":workers,"cpu_request":request_cpu,"memory_request":"1Gi" if tier=="minimal" else "2Gi","jdk_build":manifests[tier]["jdk"].get("runtimeVersion"),"archive_bytes":manifests[tier]["archive"]["sizeBytes"],"archive_sha256":manifests[tier]["archive"]["sha256"],"pod_count":details["pod_count"],"pod_restarts":details["pod_restarts"],"fetch_hash_ms":details["fetch_hash_ms"],"fetch_bytes_observed":details["fetch_bytes_observed"],"cache_hits":details["cache_hits"],"cache_misses":details["cache_misses"],"fetch_errors":details["fetch_errors"],"app_start_to_ready_ms":appdur,"load":lres,"hpa":hpa_times,"hpa_watch":hpa_watch_times,"workload_namespace_rx_bytes":rx,"workload_namespace_tx_bytes":tx,"watch_event_lines":len(events),"watch_errors":len(watches.errors),"valid":valid,"pass":valid and details["pod_restarts"]==0 and lres["failures_total"]==0}
    result["loadgen_cpu_limit"] = "2" if tier in ("standard", "larger") and workers > 128 else "500m"
    result["app_start_to_ready_complete"] = len(appdur) == details["pod_count"]
    (rawdir/"summary.json").write_text(json.dumps(result,indent=2)+"\n")
    try:kubectl("delete","pod",loadpod,"-n",NS,"--wait=false",timeout=30)
    except:pass
    try:cmd(["kubectl","delete","deployment,service,hpa",name,"-n",NS,"--ignore-not-found","--wait=true","--timeout=300s"],timeout=320)
    except:pass
    return result


def main():
    RAW.mkdir(parents=True,exist_ok=True)
    cfg=discover();cfg["tools"]=os.environ.get("MNEMO_BENCH_TOOLS_IMAGE",cfg["tools"]);refresh_registry_credentials(cfg["registry"])
    manifests={t:json.loads((ART/t/"fetch.manifest.json").read_text()) for t in TIERS}
    for t in TIERS:
        p=ART/t/manifests[t]["archive"]["file"]
        if t!="thingsboard" and (not p.exists() or p.stat().st_size!=manifests[t]["archive"]["sizeBytes"] or hashlib.sha256(p.read_bytes()).hexdigest()!=manifests[t]["archive"]["sha256"]):
            raise RuntimeError("local archive verification mismatch")
    result_file=ART/"matrix-results.jsonl"
    if result_file.exists():
        result_file.rename(ART / ("matrix-results-diagnostic-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + ".jsonl"))
    order={
      "minimal":[("uncached","single"),("direct","hpa_1_to_9"),("cold_node_cache","single"),("warm_node_cache","hpa_1_to_9"),("uncached","hpa_1_to_9"),("direct","single"),("cold_node_cache","hpa_1_to_9"),("warm_node_cache","single")],
      "standard":[("direct","single"),("cold_node_cache","hpa_1_to_9"),("warm_node_cache","single"),("uncached","hpa_1_to_9"),("direct","hpa_1_to_9"),("uncached","single"),("warm_node_cache","hpa_1_to_9"),("cold_node_cache","single")],
      "larger":[("cold_node_cache","single"),("warm_node_cache","hpa_1_to_9"),("uncached","single"),("direct","hpa_1_to_9"),("cold_node_cache","hpa_1_to_9"),("warm_node_cache","single"),("direct","single"),("uncached","hpa_1_to_9")],
      "thingsboard":[("warm_node_cache","single"),("uncached","hpa_1_to_9"),("direct","single"),("cold_node_cache","hpa_1_to_9"),("warm_node_cache","hpa_1_to_9"),("uncached","single"),("cold_node_cache","single"),("direct","hpa_1_to_9")],
    }
    for tier in TIERS:
        refresh_registry_credentials(cfg["registry"])
        cache_preflight(tier, cfg, manifests)
        for arm,topology in order[tier]:
            print("starting",tier,arm,topology,flush=True)
            try:
                result=run_cell(tier,arm,topology,cfg,manifests,LOAD_WORKERS[tier])
                with result_file.open("a") as f:f.write(json.dumps(result,separators=(",",":"))+"\n")
                print("completed",tier,arm,topology,"pass",result["pass"],"ready_ms",result["app_start_to_ready_ms"],"fetch_ms",result["fetch_hash_ms"],"cache",result["cache_hits"],result["cache_misses"],"RPS",result["load"]["rps_mean"],"failures",result["load"]["failures_total"],flush=True)
            except Exception as e:
                try:cmd(["kubectl","delete","pod","benchload-"+tier,"-n",NS,"--ignore-not-found","--wait=false"],timeout=30,check=False)
                except:pass
                for stale in ("bench-"+tier,"bench-prime-"+tier):
                    try:cmd(["kubectl","delete","deployment,service,hpa",stale,"-n",NS,"--ignore-not-found","--wait=true","--timeout=300s"],timeout=320,check=False)
                    except:pass
                failure={"tier":tier,"arm":arm,"topology":topology,"round":1,"pass":False,"error_class":type(e).__name__}
                with result_file.open("a") as f:f.write(json.dumps(failure,separators=(",",":"))+"\n")
                print("failed",tier,arm,topology,type(e).__name__,flush=True)
    print("matrix run complete",flush=True)

if __name__=="__main__":main()
