#!/usr/bin/env bash
# End-to-end test of the ANNOTATED adoption path on a real Kubernetes cluster (kind). An existing Deployment adopts
# mnemo-warmup only through three pod template annotations; Helm renders it and the offline injector (a Helm
# post-renderer) adds the init containers, the launcher, and a trainer CronJob. For each application image variant:
#
#   1. preview: the injector reports the workload and what is copied by NAME only (no value is printed),
#   2. first start: the app becomes Ready WITHOUT an archive and the original command is preserved,
#   3. training is asynchronous: a separate Job trains and uploads while the app stays Ready,
#   4. later starts load the application's classes from the archive, java is PID 1 (temurin), SIGTERM reaches java,
#   5. a workload update (changed JVM option) starts uncached with a logged reason, and rolling back hits again,
#   6. an invalid cache (archive bytes changed in the registry) and an unreachable registry both still start the app,
#   7. removal (annotation set to "false", then uninstall) restores the original Deployment and prunes what was generated,
#   8. pod start-to-Ready, with and without an archive, is measured from the pod's own condition timestamps and REPORTED
#      (median, min, max over REPEATS). Kind timings are too noisy to gate on, so only correctness fails the run.
#
# Variants: temurin (eclipse-temurin JRE, has a shell) and distroless (gcr.io/distroless/java21, no shell).
# Tools delivery: TOOLS_DELIVERY=init-container (default) or image-volume (docs/adr/0012: no init containers, the tools are an image
# volume and the fetch runs in the application container). The same scenarios run in both modes; the second needs a cluster
# with image volume support (kind with Kubernetes 1.33+ and containerd 2.1+, which is what kind's default node image has).
# Usage: scripts/e2e-kind-inject.sh [temurin] [distroless]
#   Needs docker, kind, kubectl, helm 3 (the post-renderer is a script path), java 21+, and network access.
#   REPEATS=3 (start-to-Ready samples per condition), KEEP=1 keeps the cluster and registry, ARTIFACTS=<dir> for failure logs,
#   MNEMO_WARMUP_JAR=<path> to use a built CLI jar (default: warmup-cli/target/mnemo-warmup.jar, built if missing).
#
# What this does NOT verify (stated in the output): other CPU architectures than the node's, a real cloud registry or
# EKS/ECR identity, Helm 4, and clusters other than kind.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2
ROOT=$(pwd)
VARIANTS=("$@"); [ ${#VARIANTS[@]} -gt 0 ] || VARIANTS=(temurin distroless)
REPEATS=${REPEATS:-3}
TOOLS_DELIVERY=${TOOLS_DELIVERY:-init-container}
case "$TOOLS_DELIVERY" in init-container|image-volume) ;; *) echo "TOOLS_DELIVERY must be init-container or image-volume"; exit 2 ;; esac
RUN="$$-$(date +%s)"
CLUSTER="mw-inj-$RUN"
CTX="kind-$CLUSTER"
REG="mw-inj-registry-$RUN"
TOOLS_TAG="inj-$RUN"
TOOLS_IMAGE="mnemo-warmup-tools:$TOOLS_TAG"
ARTIFACTS="${ARTIFACTS:-/tmp/mw-inj-$RUN}"
BIN=$(mktemp -d)
failures=0
IMAGES=()
REPORT=()

k() { kubectl --context "$CTX" "$@"; }
ok()   { echo "  ok:   $1"; }
fail() { echo "  FAIL: $1"; failures=$((failures + 1)); }
has()  { grep -qF -- "$2" <<<"$1"; }

dump() { # <namespace>
  mkdir -p "$ARTIFACTS"
  {
    k get pods,jobs,cronjobs,leases -A -o wide
    k -n "$1" describe pods
    for p in $(k -n "$1" get pods -o name); do
      for c in $(k -n "$1" get "$p" -o jsonpath='{.spec.initContainers[*].name} {.spec.containers[*].name}'); do
        echo "===== $p / $c"; k -n "$1" logs "$p" -c "$c" --tail=200 2>&1
      done
    done
    k -n "$1" get events --sort-by=.lastTimestamp
  } >"$ARTIFACTS/dump-$1.txt" 2>&1
  echo "  (logs written to $ARTIFACTS/dump-$1.txt)"
}

summary() {
  echo
  echo "== start-to-Ready (tools delivery: $TOOLS_DELIVERY; seconds from pod conditions, which have 1 s resolution; $REPEATS samples each; node architecture: ${NODE_ARCH:-unknown})"
  printf '%s\n' "${REPORT[@]+"${REPORT[@]}"}"
  echo
  echo "== not verified by this run"
  echo "  - CPU architectures other than ${NODE_ARCH:-the node architecture} (images are built for the host; run on the other architecture to cover it)"
  echo "  - a cloud registry, EKS/ECR workload identity, Helm 4, and clusters other than kind"
  if [ "$failures" -eq 0 ]; then echo "ALL OK"; else echo "$failures FAILED"; fi
}

die() { # <namespace> <message>
  echo "  FATAL: $2"; dump "$1"; failures=$((failures + 1)); summary; exit 1
}

cleanup() {
  rm -rf "$BIN"
  if [ "${KEEP:-}" = 1 ]; then echo "KEEP=1: cluster $CLUSTER and registry $REG left running (context $CTX)"; return; fi
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
  docker rm -f "$REG" >/dev/null 2>&1 || true
  for i in ${IMAGES[@]+"${IMAGES[@]}"}; do docker image rm -f "$i" >/dev/null 2>&1 || true; done
}
trap cleanup EXIT

for tool in docker kind kubectl helm java; do command -v "$tool" >/dev/null || { echo "missing: $tool"; exit 2; }; done
case "$(helm version --short 2>/dev/null)" in v3.*) ;; *) echo "Helm 3 is required (the post-renderer is a script path); found: $(helm version --short 2>&1)"; exit 2 ;; esac

JAR=${MNEMO_WARMUP_JAR:-warmup-cli/target/mnemo-warmup.jar}
# rebuilt when missing or when any source is newer: the injector under test must be this checkout's
if [ ! -f "$JAR" ] || [ -n "$(find warmup-core/src warmup-cli/src pom.xml warmup-core/pom.xml warmup-cli/pom.xml -type f -newer "$JAR" 2>/dev/null | head -1)" ]; then
  echo "== building the CLI jar"
  mvn -B -q -DskipTests -Dmaven.javadoc.skip=true -Djacoco.skip=true -pl warmup-cli -am package >/dev/null 2>&1 || { echo "CLI build failed"; exit 1; }
fi
JAR=$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")
# the Helm post-renderer adapter and the preview both call `mnemo-warmup` on PATH
printf '#!/bin/sh\nexec java -jar "%s" "$@"\n' "$JAR" >"$BIN/mnemo-warmup"; chmod +x "$BIN/mnemo-warmup"
export PATH="$BIN:$PATH"

# ---- helpers shared by the scenarios -------------------------------------------------------------------------------

epoch() { date -u -d "$1" +%s 2>/dev/null || date -u -j -f "%Y-%m-%dT%H:%M:%SZ" "$1" +%s; }

# the live (not terminating) pod of the Deployment that is Ready; empty if there is none
ready_pod() {
  k -n "$NS" get pods -l app=demo -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.metadata.deletionTimestamp}{"\t"}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2>/dev/null \
    | awk -F'\t' '$2 == "" && $3 == "True" { print $1; exit }'
}

# waits for the Deployment's pod to be Ready; sets POD
wait_ready() { # <seconds>
  local i
  for i in $(seq 1 "$1"); do
    POD=$(ready_pod)
    [ -n "$POD" ] && return 0
    sleep 1
  done
  return 1
}

# seconds from the pod's creation to Initialized (the init containers, including fetch) and to Ready
sample() { # sets T_INIT T_TOTAL T_JVM
  local line created init ready
  line=$(k -n "$NS" get pod "$POD" -o jsonpath='{.metadata.creationTimestamp} {.status.conditions[?(@.type=="Initialized")].lastTransitionTime} {.status.conditions[?(@.type=="Ready")].lastTransitionTime}')
  read -r created init ready <<<"$line"
  T_INIT=$(( $(epoch "$init") - $(epoch "$created") ))
  T_TOTAL=$(( $(epoch "$ready") - $(epoch "$created") ))
  T_JVM=$(started_in); T_JVM=${T_JVM:-0}
}

# replaces the pod (the Deployment recreates it) and waits for the new one; the old pod keeps terminating meanwhile
replace_pod() {
  local old=$POD
  k -n "$NS" delete pod "$old" --wait=false >/dev/null
  local i
  for i in $(seq 1 300); do
    POD=$(ready_pod)
    [ -n "$POD" ] && [ "$POD" != "$old" ] && return 0
    sleep 1
  done
  return 1
}

median() { printf '%s\n' "$@" | sort -n | awk '{ a[NR] = $1 } END { if (NR % 2) print a[(NR + 1) / 2]; else print (a[NR / 2] + a[NR / 2 + 1]) / 2 }'; }
minmax() { printf '%s\n' "$@" | sort -n | awk 'NR == 1 { lo = $1 } { hi = $1 } END { print lo "-" hi }'; }

# class-load line of the application's own class; FAILS (prints nothing) if missing, so a check cannot pass by not looking
source_line() { local logs; logs=$(k -n "$NS" logs "$POD" -c app 2>/dev/null) || return 1; grep -m1 "DemoApplication source:" <<<"$logs"; }
# the JVM's own "Started ... in N seconds" figure for the current pod (finer than pod condition timestamps)
started_in() { local logs; logs=$(k -n "$NS" logs "$POD" -c app 2>/dev/null) || return 1; sed -n 's/.*Started [A-Za-z]* in \([0-9.]*\) seconds.*/\1/p' <<<"$logs" | head -1; }
# fetch's output for the current pod: its own init container, or (image-volume) the lines it printed in the application container
fetch_logs() {
  if [ "$TOOLS_DELIVERY" = image-volume ]; then
    k -n "$NS" logs "$POD" -c app 2>&1 | grep -E '^(fetch:|Archive:|Flags:|No usable archive|mnemo-launcher:)' || true
  else
    k -n "$NS" logs "$POD" -c mnemo-warmup-fetch 2>&1
  fi
}
cronjob() { k -n "$NS" get cronjob -l warmup.mnemo.io/owner=demo -o name | head -1 | sed 's|.*/||'; }

deploy() { # <extra helm args...>; installs or upgrades the annotated chart through the injector
  helm --kube-context "$CTX" upgrade --install demo examples/annotated-app -n "$NS" -f "$VALUES" \
    --post-renderer "$ROOT/scripts/mnemo-warmup-inject" \
    --post-renderer-args="--namespace=$NS" --post-renderer-args="--tools-image=$TOOLS_IMAGE" \
    "$@"
}

# ---- images, cluster, registry -------------------------------------------------------------------------------------

echo "== building images"
docker build -q -f image/Dockerfile -t "$TOOLS_IMAGE" --build-arg VERSION=e2e . >/dev/null || { echo "tools image build failed"; exit 1; }
IMAGES+=("$TOOLS_IMAGE")
for v in "${VARIANTS[@]}"; do
  case "$v" in
    temurin)    docker build -q --build-arg JDK=21 -t "mnemo-example:temurin-$RUN" examples/spring-boot >/dev/null || { echo "temurin image build failed"; exit 1; } ;;
    distroless) docker build -q -f examples/spring-boot/Dockerfile.distroless -t "mnemo-example:distroless-$RUN" examples/spring-boot >/dev/null || { echo "distroless image build failed"; exit 1; } ;;
    *) echo "unknown variant: $v (use temurin or distroless)"; exit 2 ;;
  esac
  IMAGES+=("mnemo-example:$v-$RUN")
done

echo "== starting kind cluster $CLUSTER and the archive registry"
kind create cluster --name "$CLUSTER" ${KIND_NODE_IMAGE:+--image "$KIND_NODE_IMAGE"} --wait 180s >/dev/null 2>&1 || { echo "kind create failed"; exit 1; }
docker run -d --name "$REG" --network kind registry:2 >/dev/null || { echo "registry start failed"; exit 1; }
REG_IP=$(docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' "$REG")
[ -n "$REG_IP" ] || { echo "no registry address on the kind network"; exit 1; }
for img in "${IMAGES[@]}"; do kind load docker-image "$img" --name "$CLUSTER" >/dev/null 2>&1 || { echo "kind load failed for $img"; exit 1; }; done
NODE_ARCH=$(k get nodes -o jsonpath='{.items[0].status.nodeInfo.architecture}')

# ---- one pass per application image --------------------------------------------------------------------------------

for v in "${VARIANTS[@]}"; do
  NS="inj-$v"
  STORE="oci://$REG_IP:5000/mnemo/$v"
  echo "== variant: $v (namespace $NS, node architecture $NODE_ARCH)"
  k create namespace "$NS" >/dev/null
  VALUES="$BIN/values-$v.yaml"
  cat >"$VALUES" <<YAML
image: { repository: mnemo-example, tag: "$v-$RUN", pullPolicy: Never }
# class-load events to stdout: works without a shell, so the same check covers the distroless image
javaOptions: ["-Xlog:class+load:stdout"]
warmup: { enabled: true, store: "$STORE", plainHttp: true, toolsDelivery: "$TOOLS_DELIVERY" }
YAML

  # ---- 1. preview ----
  preview=$(helm --kube-context "$CTX" template demo examples/annotated-app -n "$NS" -f "$VALUES" | mnemo-warmup inject --namespace "$NS" --tools-image "$TOOLS_IMAGE" --preview 2>&1)
  if has "$preview" "container=app" && has "$preview" "store=$STORE" && has "$preview" "APP_GREETING"; then ok "the preview names the workload, the store and the copied environment variable"; else fail "preview: $preview"; fi
  if ! has "$preview" "hello-from-the-workload"; then ok "the preview prints names only, never a value"; else fail "the preview leaked a value: $preview"; fi

  # ---- 2. first start: no archive ----
  deploy --wait --timeout 6m >/dev/null 2>&1 || die "$NS" "helm install through the injector did not become ready (the app must start without an archive)"
  wait_ready 60 || die "$NS" "no Ready pod after install"
  ok "the app becomes Ready without an archive"
  fetch_log=$(fetch_logs)
  if has "$fetch_log" "No usable archive"; then ok "fetch reports no usable archive and exits 0"; else fail "fetch log: $fetch_log"; fi
  if line=$(source_line) && has "$line" "DemoApplication source: file:"; then ok "app classes load from the JAR (no archive yet)"; else fail "expected the class to load from the JAR, got: '${line:-<none>}'"; fi
  args=$(k -n "$NS" get deploy demo -o jsonpath='{.spec.template.spec.containers[0].args}')
  if has "$args" '"--","java","-Xlog:class+load:stdout","-jar","/app/app.jar"'; then ok "the original command is preserved after the launcher (java, options, -jar, JAR)"; else fail "container args: $args"; fi
  init_names=$(k -n "$NS" get pod "$POD" -o jsonpath='{.spec.initContainers[*].name}')
  tools_vol=$(k -n "$NS" get pod "$POD" -o jsonpath='{.spec.volumes[?(@.name=="mnemo-tools")].image.reference}')
  if [ "$TOOLS_DELIVERY" = image-volume ]; then
    if [ -z "$init_names" ]; then ok "the pod has no init containers"; else fail "init containers in image-volume mode: $init_names"; fi
    if [ "$tools_vol" = "$TOOLS_IMAGE" ]; then ok "the tools are an image volume of the tools image"; else fail "tools volume reference: '$tools_vol'"; fi
    if has "$args" '"exec","--out","/mnemo-cache"'; then ok "the launcher fetches first, with the fetch options before the --"; else fail "container args: $args"; fi
    cmd0=$(k -n "$NS" get deploy demo -o jsonpath='{.spec.template.spec.containers[0].command[0]}')
    if [ "$cmd0" = /mnemo-tools/opt/mnemo-warmup/bin/mnemo-launcher ]; then ok "the application starts through the launcher in the image volume"; else fail "command: $cmd0"; fi
  else
    if [ "$init_names" = "mnemo-warmup-install mnemo-warmup-fetch" ]; then ok "the pod has the install and fetch init containers"; else fail "init containers: '$init_names'"; fi
  fi
  if [ "$v" = temurin ]; then
    cmdline=$(k -n "$NS" exec "$POD" -c app -- sh -c 'tr "\0" " " </proc/1/cmdline' 2>&1)
    if has "$cmdline" "java" && ! has "$cmdline" "mnemo-launcher"; then ok "java is PID 1 (the launcher replaced itself)"; else fail "PID 1 is: $cmdline"; fi
  fi

  # uncached start-to-Ready samples: the first pod came from the install, the rest are fresh pods with still no archive
  UNCACHED_TOTAL=(); UNCACHED_INIT=(); UNCACHED_JVM=()
  sample; UNCACHED_TOTAL+=("$T_TOTAL"); UNCACHED_INIT+=("$T_INIT"); UNCACHED_JVM+=("$T_JVM")
  for _ in $(seq 2 "$REPEATS"); do
    replace_pod || die "$NS" "the replacement pod did not become Ready"
    sample; UNCACHED_TOTAL+=("$T_TOTAL"); UNCACHED_INIT+=("$T_INIT"); UNCACHED_JVM+=("$T_JVM")
  done

  # ---- 3. asynchronous training ----
  k -n "$NS" get jobs -o name | grep -q . && fail "a training job exists before anything started it"
  k -n "$NS" create job t1 --from="cronjob/$(cronjob)" >/dev/null || die "$NS" "cannot create the training job"
  ready_during=yes
  for _ in $(seq 1 600); do
    [ -n "$(ready_pod)" ] || ready_during=no
    st=$(k -n "$NS" get job t1 -o jsonpath='{.status.succeeded}' 2>/dev/null)
    [ "$st" = 1 ] && break
    sleep 1
  done
  [ "$(k -n "$NS" get job t1 -o jsonpath='{.status.succeeded}')" = 1 ] || die "$NS" "the training job did not complete"
  train_log=$(k -n "$NS" logs job/t1 -c trainer 2>&1)
  if has "$train_log" "train: TRAINED"; then ok "the trainer trained and uploaded"; else fail "trainer log: $train_log"; fi
  if [ "$ready_during" = yes ]; then ok "the app stayed Ready while the trainer ran"; else fail "the app lost readiness during training"; fi
  if [ -z "$(k -n "$NS" get leases -o name 2>/dev/null | grep mnemo-warmup || true)" ]; then ok "the Lease was released"; else fail "a Lease was left behind"; fi
  k -n "$NS" create job t2 --from="cronjob/$(cronjob)" >/dev/null || die "$NS" "cannot create the second job"
  k -n "$NS" wait --for=condition=complete job/t2 --timeout=300s >/dev/null 2>&1 || die "$NS" "the second job did not complete"
  if has "$(k -n "$NS" logs job/t2 -c trainer 2>&1)" "ALREADY_STORED"; then ok "a second run finds a complete entry and does not train"; else fail "second run: $(k -n "$NS" logs job/t2 -c trainer 2>&1)"; fi

  # ---- 4. cached starts; the first one also tests that SIGTERM reaches java ----
  CACHED_TOTAL=(); CACHED_INIT=(); CACHED_JVM=()
  old=$POD
  start=$(date +%s)
  k -n "$NS" delete pod "$old" --wait=true --timeout=90s >/dev/null 2>&1
  elapsed=$(( $(date +%s) - start ))
  if [ "$elapsed" -lt 25 ]; then ok "SIGTERM reached java: the pod was gone in ${elapsed}s (a swallowed signal would take the full 30s grace period)"; else fail "deleting the pod took ${elapsed}s"; fi
  wait_ready 300 || die "$NS" "the replacement pod did not become Ready"
  fetch_log=$(fetch_logs)
  if has "$fetch_log" "(APP_CDS)"; then ok "fetch found the APP_CDS archive"; else fail "fetch log: $fetch_log"; fi
  if has "$(k -n "$NS" logs "$POD" -c app 2>&1)" "using APP_CDS archive"; then ok "run validated the archive for this launch"; else fail "app log: $(k -n "$NS" logs "$POD" -c app 2>&1 | grep 'run:')"; fi
  if line=$(source_line) && has "$line" "DemoApplication source: shared objects file"; then ok "the application's own class loads from the archive"; else fail "expected the class to load from the archive, got: '${line:-<none>}'"; fi
  sample; CACHED_TOTAL+=("$T_TOTAL"); CACHED_INIT+=("$T_INIT"); CACHED_JVM+=("$T_JVM")
  for _ in $(seq 2 "$REPEATS"); do
    replace_pod || die "$NS" "the replacement pod did not become Ready"
    sample; CACHED_TOTAL+=("$T_TOTAL"); CACHED_INIT+=("$T_INIT"); CACHED_JVM+=("$T_JVM")
  done

  # ---- 5. workload update and rollback ----
  # The injector hands the JVM options to fetch and to the trainer, so a changed option is a NEW fingerprint: the new
  # pods find no archive (uncached, logged), the trainer produces one for the new options, and rolling back to the old
  # options still finds the old archive.
  UPDATE=(--set 'javaOptions={-Xlog:class+load:stdout,-Xmx256m}')
  deploy --wait --timeout 6m "${UPDATE[@]}" >/dev/null 2>&1 || die "$NS" "the updated Deployment did not become Ready"
  wait_ready 120 || die "$NS" "no Ready pod after the workload update"
  if has "$(fetch_logs)" "No usable archive"; then ok "a changed JVM option is a new fingerprint: the updated pod starts uncached with fetch's reason logged"; else fail "fetch log: $(fetch_logs)"; fi
  if line=$(source_line) && has "$line" "DemoApplication source: file:"; then ok "and the updated pod runs Ready from the JAR"; else fail "expected the class to load from the JAR, got: '${line:-<none>}'"; fi
  k -n "$NS" create job t3 --from="cronjob/$(cronjob)" >/dev/null || die "$NS" "cannot create the training job for the update"
  k -n "$NS" wait --for=condition=complete job/t3 --timeout=600s >/dev/null 2>&1 || die "$NS" "the training job for the update did not complete"
  if has "$(k -n "$NS" logs job/t3 -c trainer 2>&1)" "train: TRAINED"; then ok "the trainer (re-rendered with the new options) trains an archive for the update"; else fail "t3: $(k -n "$NS" logs job/t3 -c trainer 2>&1)"; fi
  replace_pod || die "$NS" "the replacement pod did not become Ready"
  if line=$(source_line) && has "$line" "DemoApplication source: shared objects file"; then ok "the updated workload now starts from its own archive"; else fail "after training the update: '${line:-<none>}'"; fi
  deploy --wait --timeout 6m >/dev/null 2>&1 || die "$NS" "rolling back the update did not become Ready"
  wait_ready 120 || die "$NS" "no Ready pod after the rollback"
  if line=$(source_line) && has "$line" "DemoApplication source: shared objects file"; then ok "rolling back to the original options uses the original archive"; else fail "after rollback: '${line:-<none>}'"; fi

  # ---- 6a. invalid cache: same-size change to the archive blob in the registry (the digest check must catch it) ----
  # The archive THIS pod fetched: its fingerprint names the tag, the tag's manifest names the layer. (After the update step
  # the repository holds two archives, so "the big blob in the repository" could be the wrong one and the test would corrupt
  # an archive nobody reads. CI on amd64 hit exactly that.)
  fp=$(fetch_logs | sed -n 's/.*fingerprint sha256:\([0-9a-f]*\).*/\1/p' | head -1)
  [ -n "$fp" ] || die "$NS" "cannot read the fingerprint from the fetch log"
  manifest=$(docker exec "$REG" wget -qO- --header 'Accept: application/vnd.oci.image.manifest.v1+json' "http://127.0.0.1:5000/v2/mnemo/$v/manifests/fp-$fp") \
    || die "$NS" "cannot read the manifest of fp-$fp from the registry"
  layer=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["layers"][0]["digest"].split(":")[1])' <<<"$manifest") \
    || die "$NS" "cannot find the archive layer in the manifest"
  blob="/var/lib/registry/docker/registry/v2/blobs/sha256/$(cut -c1-2 <<<"$layer")/$layer/data"
  [ "$(docker exec "$REG" sh -c "wc -c <$blob")" -gt 1000000 ] || die "$NS" "the archive blob is missing or smaller than 1 MB"
  docker exec "$REG" cp "$blob" "$blob.bak" || die "$NS" "cannot back up the archive blob"
  before=$(docker exec "$REG" sha256sum "$blob" | cut -d' ' -f1)
  # overwrite 4 KiB of the middle of the file with random bytes (same size, so only the digest check can catch it)
  docker exec "$REG" sh -c "head -c 4096 /dev/urandom | dd of=$blob bs=4096 seek=2 conv=notrunc" >/dev/null 2>&1 || die "$NS" "cannot corrupt the archive blob"
  after=$(docker exec "$REG" sha256sum "$blob" | cut -d' ' -f1)
  [ "$before" != "$after" ] || die "$NS" "the corruption step did not change the archive blob; the test would prove nothing"
  replace_pod || die "$NS" "the pod did not become Ready with a corrupted archive in the store"
  ok "the app becomes Ready when the stored archive is corrupted"
  fetch_log=$(fetch_logs)
  if has "$fetch_log" "No usable archive"; then ok "fetch rejected the corrupted archive (digest check) and exits 0"; else fail "fetch log: $fetch_log"; fi
  if line=$(source_line) && has "$line" "DemoApplication source: file:"; then ok "and the pod runs uncached"; else fail "got: '${line:-<none>}'"; fi
  docker exec "$REG" mv "$blob.bak" "$blob" || die "$NS" "cannot restore the archive blob"

  # ---- 6b. unreachable registry ----
  docker stop "$REG" >/dev/null
  start=$(date +%s)
  replace_pod || { docker start "$REG" >/dev/null; die "$NS" "the pod did not become Ready while the registry was down"; }
  down_secs=$(( $(date +%s) - start ))
  ok "the app becomes Ready while the registry is unreachable (${down_secs}s from deletion)"
  fetch_log=$(fetch_logs)
  if has "$fetch_log" "No usable archive" && has "$fetch_log" "fetch:"; then ok "fetch logged the reason and exited 0"; else fail "fetch log: $fetch_log"; fi
  docker start "$REG" >/dev/null
  [ "$(docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' "$REG")" = "$REG_IP" ] || die "$NS" "the registry changed address after restart"
  sleep 3
  replace_pod || die "$NS" "the pod did not become Ready after the registry came back"
  if line=$(source_line) && has "$line" "DemoApplication source: shared objects file"; then ok "once the registry is back, new pods use the archive again"; else fail "after the registry returned: '${line:-<none>}'"; fi

  # ---- 7. removal ----
  deploy --wait --timeout 6m --set warmup.enabled=false >/dev/null 2>&1 || die "$NS" "the Deployment with adoption turned off did not become Ready"
  wait_ready 120 || die "$NS" "no Ready pod after removal"
  spec=$(k -n "$NS" get deploy demo -o jsonpath='{.spec.template.spec.containers[0].command}{"|"}{.spec.template.spec.initContainers}{"|"}{.spec.template.spec.volumes}')
  if [ "$spec" = '["java"]||' ]; then ok "with adoption off the Deployment is back to its original command, no init containers, no volumes"; else fail "spec after removal: $spec"; fi
  leftovers=$(k -n "$NS" get cronjob,serviceaccount,role,rolebinding -l warmup.mnemo.io/owner=demo -o name 2>/dev/null)
  if [ -z "$leftovers" ]; then ok "the generated CronJob, ServiceAccount, Role and RoleBinding were pruned"; else fail "left behind: $leftovers"; fi

  # ---- 8. timings ----
  REPORT+=("  $v  without archive: median $(median "${UNCACHED_TOTAL[@]}") (range $(minmax "${UNCACHED_TOTAL[@]}")), of which init containers median $(median "${UNCACHED_INIT[@]}")")
  REPORT+=("  $v  with archive:    median $(median "${CACHED_TOTAL[@]}") (range $(minmax "${CACHED_TOTAL[@]}")), of which init containers median $(median "${CACHED_INIT[@]}")")
  REPORT+=("  $v  JVM-reported application start (Started ... in): without archive median $(median "${UNCACHED_JVM[@]}") s, with archive median $(median "${CACHED_JVM[@]}") s")
done

summary
[ "$failures" -eq 0 ]
