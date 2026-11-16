#!/usr/bin/env bash
# End-to-end test of isolated training (docs/adr/0013, stage B) on a disposable kind cluster, for one JDK (default 21):
#
#   1. builds the tools image, the example Spring Boot image and a trusted publisher image (FROM the example image, so the JDK
#      and the application files are identical), starts kind and an anonymous registry on the kind network,
#   2. prepares a handoff PVC (local-path) whose root group is the handoff fsGroup, as an operator would,
#   3. FORBIDDEN SECRET: a training template that mounts the signing Secret is refused, and no training pod is created,
#   4. TIMEOUT: a training pod that cannot finish in time is deleted, the run fails and its handoff directory is kept,
#   5. trains in a separate pod (UID 10001, its own ServiceAccount, no API token, no signing key) while the coordinator
#      (UID 65532) signs and pushes; the handoff directory is removed afterwards,
#   6. a second run finds the signed archive and creates no training pod,
#   7. restarted application pods fetch the signed archive and load their classes from it.
#
# Mutable local image tags are allowed through trainer.isolation.allowMutableImages; that is for this test only.
# Usage: scripts/e2e-kind-isolated.sh [jdk]   Needs docker, kind, kubectl, helm. KEEP=1 keeps the cluster for debugging.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2
JDK=${1:-21}
RUN="$$-$(date +%s)"
CLUSTER="mw-iso-$RUN"
CTX="kind-$CLUSTER"
REG="mw-iso-registry-$RUN"
TAG="iso-$RUN"
ARTIFACTS="${ARTIFACTS:-/tmp/mw-iso-$RUN}"
WORK=$(mktemp -d)
NS=iso
FS_GROUP=65532
failures=0
IMAGES=()

k() { kubectl --context "$CTX" "$@"; }
ok()   { echo "  ok:   $1"; }
fail() { echo "  FAIL: $1"; failures=$((failures + 1)); }
has()  { grep -qF -- "$2" <<<"$1"; }

dump() {
  mkdir -p "$ARTIFACTS"
  {
    k get pods,jobs,cronjobs,leases,pvc -A -o wide
    k -n "$NS" describe pods
    for p in $(k -n "$NS" get pods -o name); do
      for c in $(k -n "$NS" get "$p" -o jsonpath='{.spec.initContainers[*].name} {.spec.containers[*].name}'); do
        echo "===== $p / $c"; k -n "$NS" logs "$p" -c "$c" --tail=200 2>&1
      done
    done
    k -n "$NS" get events --sort-by=.lastTimestamp
  } >"$ARTIFACTS/dump.txt" 2>&1
  echo "  (logs written to $ARTIFACTS/dump.txt)"
}
summary() { if [ "$failures" -eq 0 ]; then echo "ALL OK"; else echo "$failures FAILED"; [ -f "$ARTIFACTS/dump.txt" ] || dump; fi; }
die() { echo "  FATAL: $1"; dump; failures=$((failures + 1)); summary; exit 1; }

cleanup() {
  [ -n "${WATCH_PID:-}" ] && kill "$WATCH_PID" 2>/dev/null
  rm -rf "$WORK"
  if [ "${KEEP:-}" = 1 ]; then echo "KEEP=1: cluster $CLUSTER and registry $REG left running (context $CTX)"; return; fi
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
  docker rm -f "$REG" >/dev/null 2>&1 || true
  for i in ${IMAGES[@]+"${IMAGES[@]}"}; do docker image rm -f "$i" >/dev/null 2>&1 || true; done
}
trap cleanup EXIT

for tool in docker kind kubectl helm; do command -v "$tool" >/dev/null || { echo "missing: $tool"; exit 2; }; done

echo "== building images"
TOOLS_IMAGE="mnemo-warmup-tools:$TAG"; APP_IMAGE="mnemo-example:$JDK-$RUN"; PUBLISHER_IMAGE="mnemo-publisher:$JDK-$RUN"
docker build -q -f image/Dockerfile -t "$TOOLS_IMAGE" --build-arg VERSION=e2e . >/dev/null || { echo "tools image build failed"; exit 1; }
IMAGES+=("$TOOLS_IMAGE")
docker build -q --build-arg "JDK=$JDK" -t "$APP_IMAGE" examples/spring-boot >/dev/null || { echo "example image build failed"; exit 1; }
IMAGES+=("$APP_IMAGE")
# The trusted publisher: the same JDK build and application files as the application image, so the fingerprints agree.
printf 'FROM %s\nUSER 65532\n' "$APP_IMAGE" | docker build -q -t "$PUBLISHER_IMAGE" - >/dev/null || { echo "publisher image build failed"; exit 1; }
IMAGES+=("$PUBLISHER_IMAGE")
PUBLISHER_JAVA=$(docker run --rm --entrypoint sh "$PUBLISHER_IMAGE" -c 'readlink -f "$(command -v java)"') || { echo "no java in the publisher image"; exit 1; }

echo "== starting kind cluster $CLUSTER and the archive registry"
kind create cluster --name "$CLUSTER" ${KIND_NODE_IMAGE:+--image "$KIND_NODE_IMAGE"} --wait 180s >/dev/null 2>&1 || { echo "kind create failed"; exit 1; }
docker run -d --name "$REG" --network kind registry:2 >/dev/null || { echo "registry start failed"; exit 1; }
REG_IP=$(docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' "$REG")
[ -n "$REG_IP" ] || { echo "no registry address on the kind network"; exit 1; }
for img in "${IMAGES[@]}"; do kind load docker-image "$img" --name "$CLUSTER" >/dev/null 2>&1 || { echo "kind load failed for $img"; exit 1; }; done

mkdir -p "$WORK/cli" "$WORK/keys" "$WORK/deploy" "$WORK/examples"
cid=$(docker create "$TOOLS_IMAGE" true) || exit 1
docker cp "$cid:/opt/mnemo-warmup/mnemo-warmup.jar" "$WORK/cli/mnemo-warmup.jar" >/dev/null || { docker rm "$cid" >/dev/null; echo "no CLI jar in the tools image"; exit 1; }
docker rm "$cid" >/dev/null
KEY=$(docker run --rm --user "$(id -u):$(id -g)" -v "$WORK/cli:/cli:ro" -v "$WORK/keys:/keys" --entrypoint java "$APP_IMAGE" \
  -jar /cli/mnemo-warmup.jar keygen --out /keys/a.key 2>/dev/null | sed -n 's/^Public key: *//p')
[ -n "$KEY" ] || { echo "keygen failed"; exit 1; }
cp -R deploy/helm "$WORK/deploy/helm"; cp -R examples/helm "$WORK/examples/helm"
CHART="$WORK/examples/helm/example-app"
helm dependency build "$CHART" >/dev/null 2>&1 || { echo "helm dependency build failed"; exit 1; }

echo "== JDK $JDK (namespace $NS)"
k create namespace "$NS" >/dev/null
k -n "$NS" create secret generic sign-a --from-file=signing.key="$WORK/keys/a.key" >/dev/null || die "cannot create the signing Secret"

# The handoff PVC, prepared by the operator: local-path volumes ignore fsGroup, so its root group is set once, by hand.
k -n "$NS" apply -f - >/dev/null <<YAML || die "cannot create the handoff PVC"
apiVersion: v1
kind: PersistentVolumeClaim
metadata: {name: handoff}
spec: {accessModes: [ReadWriteOnce], resources: {requests: {storage: 1Gi}}}
---
apiVersion: v1
kind: Pod
metadata: {name: handoff-prep}
spec:
  restartPolicy: Never
  containers:
    - {name: prep, image: "$APP_IMAGE", imagePullPolicy: Never, command: [sh, -c, "chgrp $FS_GROUP /h && chmod 2770 /h && ls -ldn /h"],
       volumeMounts: [{name: h, mountPath: /h}]}
  volumes: [{name: h, persistentVolumeClaim: {claimName: handoff}}]
YAML
k -n "$NS" wait --for=jsonpath='{.status.phase}'=Succeeded pod/handoff-prep --timeout=180s >/dev/null 2>&1 || die "the handoff PVC could not be prepared"
k -n "$NS" delete pod handoff-prep --wait >/dev/null
PV_PATH=$(k get pv "$(k -n "$NS" get pvc handoff -o jsonpath='{.spec.volumeName}')" -o jsonpath='{.spec.hostPath.path}{.spec.local.path}')
NODE="$CLUSTER-control-plane"
handoff_dirs() { docker exec "$NODE" sh -c "ls -1 '$PV_PATH' 2>/dev/null | grep '^mnemo-run-' || true"; }

values() { # <extra yaml file or ''>
  cat >"$WORK/values.yaml" <<YAML
warmup:
  app:
    image: { repository: mnemo-example, tag: "$JDK-$RUN", pullPolicy: Never }
  tools:
    image: { repository: mnemo-warmup-tools, tag: "$TAG", pullPolicy: Never }
  store: { url: "oci://$REG_IP:5000/mnemo/iso-$JDK", plainHttp: true }
  signing: { publicKeys: "$KEY", keySecret: sign-a }
  trainer:
    suspend: true
    timeoutSeconds: 300
    securityContext: { runAsUser: 10001, runAsNonRoot: true }
    isolation:
      enabled: true
      allowMutableImages: true
      publisherImage: "$PUBLISHER_IMAGE"
      publisherJava: "$PUBLISHER_JAVA"
      handoffClaim: handoff
      fsGroup: $FS_GROUP
      trainingPodTimeoutSeconds: ${TRAIN_TIMEOUT:-600}
YAML
  [ -n "$1" ] && cat "$1" >>"$WORK/values.yaml"
  helm --kube-context "$CTX" upgrade --install ex "$CHART" -n "$NS" -f "$WORK/values.yaml" --wait --timeout 6m >"$WORK/helm.log" 2>&1
}
cronjob() { k -n "$NS" get cronjob -l app.kubernetes.io/instance=ex -o name | head -1 | sed 's|.*/||'; }
app_pods() {
  k -n "$NS" get pods -l app=ex-app -o jsonpath='{range .items[?(@.status.phase=="Running")]}{.metadata.name}{"\t"}{.metadata.deletionTimestamp}{"\n"}{end}' \
    | awk -F'\t' '$2 == "" { print "pod/" $1 }'
}
settle() {
  local i
  for i in $(seq 1 90); do
    if [ "$(k -n "$NS" get pods -l app=ex-app -o name | wc -l | tr -d ' ')" = 2 ] \
       && [ -z "$(k -n "$NS" get pods -l app=ex-app -o jsonpath='{.items[*].metadata.deletionTimestamp}')" ] \
       && [ "$(k -n "$NS" get pods -l app=ex-app -o jsonpath='{.items[*].status.containerStatuses[*].ready}' | tr ' ' '\n' | grep -c true)" = 2 ]; then return 0; fi
    sleep 2
  done
  die "the rollout did not settle"
}
# Records every training pod the coordinator creates, as JSON, while a job runs: the coordinator deletes them afterwards.
# (The poll can miss the short Succeeded phase before deletion; publish-only-after-Succeeded is a unit test.)
watch_training() { # <file>
  : >"$1"
  ( while :; do
      k -n "$NS" get pods -o json 2>/dev/null | python3 -c '
import json, sys
for p in json.load(sys.stdin)["items"]:
    if p["metadata"]["name"].startswith("mnemo-train-"): print(json.dumps(p))' >>"$1" 2>/dev/null
      sleep 1
    done ) &
  WATCH_PID=$!
}
stop_watch() { kill "$WATCH_PID" 2>/dev/null; wait "$WATCH_PID" 2>/dev/null; WATCH_PID=; }
run_job() { # <name> <complete|failed> <timeout>
  k -n "$NS" create job "$1" --from="cronjob/$(cronjob)" >/dev/null || die "cannot create job $1"
  k -n "$NS" wait --for=condition="$2" "job/$1" --timeout="$3" >/dev/null 2>&1
}
training_pods() { python3 -c 'import json,sys; print(len({json.loads(l)["metadata"]["uid"] for l in open(sys.argv[1]) if l.strip()}))' "$1"; }

# ---- A. install: the application starts without an archive ----
cat >"$WORK/forbidden.yaml" <<YAML
    volumes: [{name: stolen, secret: {secretName: sign-a}}]
    volumeMounts: [{name: stolen, mountPath: /stolen}]
YAML
values "$WORK/forbidden.yaml" || { cat "$WORK/helm.log"; die "helm install did not become ready"; }
ok "the app starts without an archive (isolated training enabled)"
roles=$(k -n "$NS" get role -o yaml)
if has "$roles" "pods"; then ok "the coordinator Role may manage pods"; else fail "coordinator Role: $roles"; fi
tsa=$(k -n "$NS" get sa -o jsonpath='{range .items[*]}{.metadata.name}={.automountServiceAccountToken}{"\n"}{end}' | grep training || true)
if has "$tsa" "=false"; then ok "the training ServiceAccount does not automount a token ($tsa)"; else fail "training ServiceAccount: '$tsa'"; fi

# ---- B. a training template that references the signing Secret is refused ----
watch_training "$WORK/pods-forbidden.jsonl"
run_job c0 failed 300s || die "a coordinator with a forbidden training template did not fail"
stop_watch
log=$(k -n "$NS" logs job/c0 -c publisher 2>&1)
if has "$log" "publisher credential Secret"; then ok "a template that mounts the signing Secret is refused"; else fail "c0 log: $log"; fi
if ! has "$log" "retained handoff" && [ -z "$(handoff_dirs)" ]; then ok "the refusal comes before any handoff directory exists"; else fail "a refused template left a handoff: $(handoff_dirs)"; fi
if [ "$(training_pods "$WORK/pods-forbidden.jsonl")" = 0 ]; then ok "and no training pod was created"; else fail "a training pod was created for a forbidden template"; fi

# ---- B2. any Secret that is not explicitly allowed is refused too (a leftover token Secret, for example) ----
k -n "$NS" create secret generic leftover-token --from-literal=token=x >/dev/null || die "cannot create the unlisted Secret"
sed 's/secretName: sign-a/secretName: leftover-token/' "$WORK/forbidden.yaml" >"$WORK/unlisted.yaml"
values "$WORK/unlisted.yaml" || { cat "$WORK/helm.log"; die "helm upgrade (unlisted Secret) failed"; }
watch_training "$WORK/pods-unlisted.jsonl"
run_job c0b failed 300s || die "a coordinator with an unlisted training Secret did not fail"
stop_watch
log=$(k -n "$NS" logs job/c0b -c publisher 2>&1)
if has "$log" "not in the allowed training Secrets"; then ok "a Secret that is not in allowedSecrets is refused"; else fail "c0b log: $log"; fi
if [ "$(training_pods "$WORK/pods-unlisted.jsonl")" = 0 ]; then ok "and no training pod was created"; else fail "a training pod was created for an unlisted Secret"; fi

# ---- B3. the training template cannot be changed in place ----
cm=$(k -n "$NS" get configmap -o name | grep training-template | head -1)
if out=$(k -n "$NS" patch "$cm" --type merge -p '{"data":{"training.yaml":"containers: []"}}' 2>&1); then fail "the template ConfigMap could be changed: $out"
elif has "$out" "immutable"; then ok "the template ConfigMap is immutable ($cm)"; else fail "unexpected patch error: $out"; fi

# ---- C. a training pod that cannot finish in time: deleted, run failed, handoff kept ----
TRAIN_TIMEOUT=3 values "" || { cat "$WORK/helm.log"; die "helm upgrade (timeout) failed"; }
watch_training "$WORK/pods-timeout.jsonl"
run_job c1 failed 300s || die "a coordinator whose training timed out did not fail"
stop_watch
log=$(k -n "$NS" logs job/c1 -c publisher 2>&1)
if has "$log" "retained handoff"; then ok "a timed-out run fails and keeps its handoff ($(grep -o 'mnemo-run-[A-Za-z0-9-]*' <<<"$log" | head -1))"; else fail "c1 log: $log"; fi
sleep 5
if [ -z "$(k -n "$NS" get pods -o name | grep mnemo-train- || true)" ]; then ok "the timed-out training pod was deleted"; else fail "training pod left: $(k -n "$NS" get pods -o name | grep mnemo-train-)"; fi
retained=$(handoff_dirs)
if [ -n "$retained" ]; then ok "the retained handoff directory is still on the PVC"; else fail "no retained handoff directory on the PVC"; fi

# ---- D. normal training: separate pod and identity, signed publication, handoff removed ----
values "" || { cat "$WORK/helm.log"; die "helm upgrade (normal) failed"; }
watch_training "$WORK/pods-train.jsonl"
run_job c2 complete 900s || die "the isolated training run did not complete"
stop_watch
log=$(k -n "$NS" logs job/c2 -c publisher 2>&1)
if has "$log" "coordinate-isolated: TRAINED" || has "$log" "coordinate-isolated: PUBLISHED"; then ok "the coordinator published the archive: $(grep 'coordinate-isolated:' <<<"$log" | head -1)"; else fail "c2 log: $log"; fi
[ "$(training_pods "$WORK/pods-train.jsonl")" = 1 ] && ok "exactly one training pod was created" || fail "training pods created: $(training_pods "$WORK/pods-train.jsonl")"
python3 - "$WORK/pods-train.jsonl" "$(k -n "$NS" get cronjob "$(cronjob)" -o jsonpath='{.spec.jobTemplate.spec.template.spec.serviceAccountName}')" \
  "$(k -n "$NS" get pods -l job-name=c2 -o jsonpath='{.items[0].metadata.name}/{.items[0].metadata.uid}')" <<'PY' >"$WORK/checks.txt"
import json, sys
pods = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
coordinator_sa = sys.argv[2]
last = {}
for p in pods: last[p["metadata"]["uid"]] = p
for p in last.values():
    spec, text = p["spec"], json.dumps(p["spec"])
    print("sa", spec.get("serviceAccountName") != coordinator_sa, spec.get("serviceAccountName"))
    print("token", spec.get("automountServiceAccountToken") is False and "kube-api-access" not in text and "serviceAccountToken" not in text, "-")
    print("secrets", not any("secret" in v for v in spec.get("volumes", [])) and "sign-a" not in text and "SIGNING_KEY" not in text and "REGISTRY_AUTH" not in text, "-")
    c = spec["containers"][0]
    print("uid", (c.get("securityContext") or {}).get("runAsUser") == 10001, (c.get("securityContext") or {}).get("runAsUser"))
    print("fsgroup", (spec.get("securityContext") or {}).get("fsGroup") == 65532, (spec.get("securityContext") or {}).get("fsGroup"))
    print("label", p["metadata"].get("labels", {}).get("warmup.mnemo.io/role") == "training", "-")
    owners = p["metadata"].get("ownerReferences", [])
    print("owner", len(owners) == 1 and owners[0]["kind"] == "Pod" and owners[0]["name"] + "/" + owners[0]["uid"] == sys.argv[3], owners[0]["name"] if owners else "none")
    print("handoff", any(m.get("subPath", "").startswith("mnemo-run-") for m in c.get("volumeMounts", [])) and "warmup.mnemo.io/handoff" in p["metadata"].get("annotations", {}), "-")
PY
while read -r what pass detail; do
  case "$what" in
    sa) msg="the training pod runs as its own ServiceAccount ($detail), not the coordinator's" ;;
    token) msg="the training pod has no Kubernetes API token" ;;
    secrets) msg="the training pod mounts no Secret and has no signing key or push credential path" ;;
    uid) msg="the training container runs as UID $detail (the coordinator runs as 65532)" ;;
    fsgroup) msg="the training pod shares the handoff group $detail" ;;
    handoff) msg="it mounts only its per-run handoff subPath and is annotated with it" ;;
    label) msg="it carries the warmup.mnemo.io/role=training label" ;;
    owner) msg="it is owned by the coordinator pod ($detail)" ;;
  esac
  if [ "$pass" = True ]; then ok "$msg"; else fail "$msg ($detail)"; fi
done <"$WORK/checks.txt"
[ -s "$WORK/checks.txt" ] || fail "no training pod was observed"
# The real training pod, as created, against Pod Security "restricted": a server-side dry run in a namespace that enforces it.
k create namespace iso-restricted >/dev/null && k label namespace iso-restricted pod-security.kubernetes.io/enforce=restricted >/dev/null
k -n iso-restricted create serviceaccount "$(k -n "$NS" get sa -o name | grep training | sed 's|.*/||')" >/dev/null
python3 - "$WORK/pods-train.jsonl" >"$WORK/restricted-pod.json" <<'PY2'
import json, sys
pod = [json.loads(l) for l in open(sys.argv[1]) if l.strip()][-1]
meta = pod["metadata"]
print(json.dumps({"apiVersion": "v1", "kind": "Pod", "metadata": {"name": meta["name"], "namespace": "iso-restricted", "labels": meta.get("labels", {})},
                  "spec": {k: v for k, v in pod["spec"].items() if k not in ("nodeName", "priority", "preemptionPolicy")}}))
PY2
if out=$(k apply --dry-run=server -f "$WORK/restricted-pod.json" 2>&1); then ok "the training pod is admitted under Pod Security restricted"; else fail "restricted admission: $out"; fi
if [ "$(handoff_dirs)" = "$retained" ]; then ok "the successful run removed its handoff; only the earlier retained one is left"; else fail "handoff dirs: $(handoff_dirs | tr '\n' ' ') (retained before: $retained)"; fi
if [ -z "$(k -n "$NS" get leases -o name 2>/dev/null | grep mnemo-warmup || true)" ]; then ok "the Lease was released"; else fail "a Lease was left behind"; fi

# ---- E. a second run finds the signed archive and does not train ----
watch_training "$WORK/pods-again.jsonl"
run_job c3 complete 300s || die "the second coordinator run did not complete"
stop_watch
log=$(k -n "$NS" logs job/c3 -c publisher 2>&1)
if has "$log" "ALREADY_STORED"; then ok "a second run finds the signed archive"; else fail "c3 log: $log"; fi
if [ "$(training_pods "$WORK/pods-again.jsonl")" = 0 ]; then ok "and creates no training pod"; else fail "a training pod was created although the archive is stored"; fi

# ---- F. application pods use the signed archive ----
k -n "$NS" rollout restart deploy/ex-app >/dev/null
k -n "$NS" rollout status deploy/ex-app --timeout=300s >/dev/null 2>&1 || die "the restarted Deployment did not become ready"
settle
pod=$(app_pods | head -1)
if [ "$JDK" -ge 25 ]; then expect_type=LEYDEN_AOT; else expect_type=APP_CDS; fi
fetch_log=$(k -n "$NS" logs "$pod" -c mnemo-warmup-fetch 2>&1)
if has "$fetch_log" "($expect_type)"; then ok "fetch verified and used the signed $expect_type archive"; else fail "fetch log: $fetch_log"; fi
app_log=$(k -n "$NS" logs "$pod" -c app 2>&1)
if has "$app_log" "run: using $expect_type archive"; then ok "run validated the archive for this launch"; else fail "app log: $(grep 'run:' <<<"$app_log")"; fi
if k -n "$NS" exec "${pod#pod/}" -c app -- sh -c 'test -s /mnemo-cache/mnemo-warmup.signature && test ! -e /var/run/mnemo-warmup/signing' >/dev/null 2>&1; then
  ok "the signature is next to the archive and the application has no private key"; else fail "signature or key check in the application container"; fi

summary
[ "$failures" -eq 0 ]
