#!/usr/bin/env bash
# A 1-to-10 Kubernetes burst against a remote OCI archive store. Use --local-registry only for harness checks.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
RUN="$$-$(date +%s)"
CLUSTER="mw-burst-$RUN"; CTX="kind-$CLUSTER"; REG="mw-burst-registry-$RUN"; PROXY="mw-burst-proxy-$RUN"; NS=bench
TOOLS_IMAGE="mnemo-warmup-tools:burst-$RUN"; APP_IMAGE="bench-spring-heavy:burst-$RUN"
STORE=""; AUTH_FILE=""; OUT=""; ROUNDS=3; LOCAL=0; SLOW=0; JDK=21; PROBE_PERIOD=1
while [ $# -gt 0 ]; do
  case "$1" in
    --store) STORE=$2; shift 2 ;;
    --auth-file) AUTH_FILE=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --rounds) ROUNDS=$2; shift 2 ;;
    --jdk) JDK=$2; shift 2 ;;
    --probe-period) PROBE_PERIOD=$2; shift 2 ;;
    --local-registry) LOCAL=1; shift ;;
    --slow-local) SLOW=1; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
[[ "$ROUNDS" =~ ^[1-9][0-9]*$ ]] || { echo "--rounds must be positive" >&2; exit 2; }
case "$JDK" in 21|25) ;; *) echo "--jdk must be 21 or 25" >&2; exit 2 ;; esac
[[ "$PROBE_PERIOD" =~ ^[1-9][0-9]*$ ]] || { echo "--probe-period must be positive" >&2; exit 2; }
[ -n "$OUT" ] || { echo "Pass --out to preserve the results" >&2; exit 2; }
if [ "$LOCAL" = 1 ]; then [ -z "$STORE" ] || { echo "Use either --store or --local-registry" >&2; exit 2; }
else [[ "$STORE" == oci://*/* ]] || { echo "Pass --store oci://host/repository" >&2; exit 2; }; fi
if [ "$SLOW" = 1 ] && { [ "$LOCAL" != 1 ] || [ -n "$AUTH_FILE" ]; }; then
  echo '--slow-local requires the anonymous --local-registry' >&2; exit 2
fi
[ -z "$AUTH_FILE" ] || [ -f "$AUTH_FILE" ] || { echo "Registry auth file does not exist" >&2; exit 2; }
for tool in docker kind kubectl helm java python3 mvn; do command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 2; }; done
case "$(helm version --short)" in v3.*) ;; *) echo "Helm 3 is required" >&2; exit 2 ;; esac
mkdir -p "$OUT/raw"; OUT=$(cd "$OUT" && pwd)
TMP=$(mktemp -d)
export KUBECONFIG="$TMP/kubeconfig"
k() { kubectl --context "$CTX" "$@"; }
node_rx_bytes() { docker exec "$CLUSTER-control-plane" cat /sys/class/net/eth0/statistics/rx_bytes; }
now_ns() { python3 -c 'import time; print(time.time_ns())'; }
cleanup() {
  if [ "${KEEP:-}" = 1 ]; then echo "Kept $CLUSTER and kubeconfig $KUBECONFIG; clean them up after inspection"; return; fi
  kind delete cluster --name "$CLUSTER" --kubeconfig "$KUBECONFIG" >/dev/null 2>&1 || true
  if [ "$SLOW" = 1 ]; then docker rm -f "$PROXY" >/dev/null 2>&1 || true; fi
  if [ "$LOCAL" = 1 ]; then docker rm -f "$REG" >/dev/null 2>&1 || true; fi
  docker image rm "$TOOLS_IMAGE" "$APP_IMAGE" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

JAR=${MNEMO_WARMUP_JAR:-warmup-cli/target/mnemo-warmup.jar}
if [ ! -f "$JAR" ] || [ -n "$(find warmup-core/src warmup-cli/src pom.xml warmup-core/pom.xml warmup-cli/pom.xml -type f -newer "$JAR" 2>/dev/null | head -1)" ]; then
  echo 'Building CLI'; mvn -q -DskipTests -Dmaven.javadoc.skip=true -Djacoco.skip=true -pl warmup-cli -am package
fi
JAR=$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")
printf '#!/bin/sh\nexec java -jar "%s" "$@"\n' "$JAR" >"$TMP/mnemo-warmup"; chmod +x "$TMP/mnemo-warmup"; export PATH="$TMP:$PATH"
echo 'Building tools and application images'
docker build -q -f image/Dockerfile -t "$TOOLS_IMAGE" --build-arg VERSION=bench . >/dev/null
docker build -q --build-arg JDK="$JDK" -t "$APP_IMAGE" benchmarks/spring-heavy >/dev/null
echo 'Creating isolated kind cluster'
kind create cluster --name "$CLUSTER" --kubeconfig "$KUBECONFIG" ${KIND_NODE_IMAGE:+--image "$KIND_NODE_IMAGE"} --wait 180s >/dev/null
if [ "$LOCAL" = 1 ]; then
  docker run -d --name "$REG" --network kind registry:2 >/dev/null
  REG_IP=$(docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' "$REG")
  STORE="oci://$REG_IP:5000/bench/archive"
  if [ "$SLOW" = 1 ]; then
    docker run -d --name "$PROXY" --network kind -v "$ROOT/scripts/bench_slow_registry_proxy.go:/src/main.go:ro" \
      -e "BENCH_REGISTRY_UPSTREAM=$REG_IP:5000" -e BENCH_DELAY_MS=500 -e BENCH_BYTES_PER_SECOND=8388608 \
      golang:1.24 go run /src/main.go >/dev/null
    PROXY_IP=$(docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' "$PROXY")
    for attempt in $(seq 1 120); do
      if docker exec "$CLUSTER-control-plane" curl -fsS --max-time 2 "http://$PROXY_IP:5000/v2/" >/dev/null 2>&1; then break; fi
      if [ "$attempt" = 120 ]; then echo 'Slow proxy failed to start' >&2; exit 1; fi
      sleep 1
    done
    STORE="oci://$PROXY_IP:5000/bench/archive"
  fi
fi
kind load docker-image "$TOOLS_IMAGE" "$APP_IMAGE" --name "$CLUSTER" >/dev/null
k create namespace "$NS" >/dev/null
if [ -n "$AUTH_FILE" ]; then
  k -n "$NS" create secret generic bench-registry --from-file=".dockerconfigjson=$AUTH_FILE" >/dev/null
fi
cat >"$TMP/values.yaml" <<YAML
image: { repository: ${APP_IMAGE%%:*}, tag: "${APP_IMAGE#*:}", pullPolicy: Never }
readinessPath: /actuator/health/readiness
readinessPeriodSeconds: $PROBE_PERIOD
readinessInitialDelaySeconds: 0
jar: /app/app.jar
extraEnv:
  - { name: MNEMO_WARMUP_TIMING, value: "1" }
resources:
  requests: { cpu: "100m", memory: "256Mi" }
  limits: { cpu: "1", memory: "512Mi" }
YAML
render() {
  local arm=$1; shift
  local args=(--set replicas=1)
  if [ "$arm" = none ]; then args+=(--set warmup.enabled=false)
  elif [ "$arm" = fault ]; then
    # A refused local connection exercises fail-open separately from real registry performance.
    args+=(--set-string warmup.store=oci://127.0.0.1:9/bench/unavailable --set warmup.plainHttp=true)
  else
    args+=(--set-string "warmup.store=$STORE")
    if [ "$LOCAL" = 1 ]; then args+=(--set warmup.plainHttp=true); fi
    if [ -n "$AUTH_FILE" ]; then args+=(--set warmup.pullSecret=bench-registry --set warmup.pushSecret=bench-registry); fi
  fi
  helm --kube-context "$CTX" upgrade --install "$arm" examples/annotated-app -n "$NS" -f "$TMP/values.yaml" \
    --post-renderer "$ROOT/scripts/mnemo-warmup-inject" --post-renderer-args="--namespace=$NS" \
    --post-renderer-args="--tools-image=$TOOLS_IMAGE" "${args[@]}" >/dev/null
}
echo 'Deploying control, unavailable-registry, and cached workloads'
render none
render cached
render fault
FAULT_CRON=$(k -n "$NS" get cronjob -l warmup.mnemo.io/owner=fault -o jsonpath='{.items[0].metadata.name}')
k -n "$NS" patch cronjob/"$FAULT_CRON" -p '{"spec":{"suspend":true}}' >/dev/null
k -n "$NS" patch cronjob/cached-mnemo-warmup -p '{"spec":{"suspend":true}}' >/dev/null 2>&1 || {
  CRON=$(k -n "$NS" get cronjob -l warmup.mnemo.io/owner=cached -o jsonpath='{.items[0].metadata.name}')
  k -n "$NS" patch cronjob/"$CRON" -p '{"spec":{"suspend":true}}' >/dev/null
}
CRON=$(k -n "$NS" get cronjob -l warmup.mnemo.io/owner=cached -o jsonpath='{.items[0].metadata.name}')
k -n "$NS" create job train --from="cronjob/$CRON" >/dev/null
if ! k -n "$NS" wait --for=condition=complete job/train --timeout=900s >/dev/null; then
  k -n "$NS" logs job/train -c trainer >"$OUT/raw/train.log" 2>&1 || true
  echo "Training failed; see $OUT/raw/train.log" >&2; exit 1
fi
k -n "$NS" logs job/train -c trainer >"$OUT/raw/train.log"
grep -q 'train: TRAINED\|train: ALREADY_STORED' "$OUT/raw/train.log" || { echo 'Training did not produce an archive' >&2; exit 1; }
# Replace the first cached pod, which may have started before training finished.
k -n "$NS" scale deploy/cached --replicas=0 >/dev/null
k -n "$NS" wait --for=delete pod -l app=cached --timeout=180s >/dev/null
k -n "$NS" scale deploy/cached --replicas=1 >/dev/null
for arm in none cached fault; do k -n "$NS" rollout status deploy/"$arm" --timeout=600s >/dev/null; done

burst() {
  local arm=$1 round=$2 dir="$OUT/raw/$1-$2" baseline pod rx_before rx_after start_ns end_ns
  mkdir -p "$dir"
  if [ "$arm" = cached ]; then
    # Recreate the single cached pod so each round measures one fresh fetch before the concurrent burst.
    k -n "$NS" scale deploy/cached --replicas=0 >/dev/null
    k -n "$NS" wait --for=delete pod -l app=cached --timeout=180s >/dev/null
    rx_before=$(node_rx_bytes); start_ns=$(now_ns)
  fi
  k -n "$NS" scale deploy/"$arm" --replicas=1 >/dev/null
  k -n "$NS" rollout status deploy/"$arm" --timeout=600s >/dev/null
  if [ "$arm" = cached ]; then
    rx_after=$(node_rx_bytes); end_ns=$(now_ns)
    printf 'mode,rx_bytes_before,rx_bytes_after,elapsed_ns\nsingle,%s,%s,%s\n' "$rx_before" "$rx_after" "$((end_ns-start_ns))" >"$dir/network.csv"
  fi
  baseline=$(k -n "$NS" get pods -l "app=$arm" -o jsonpath='{.items[0].metadata.name}')
  printf '%s\n' "$baseline" >"$dir/baseline.txt"
  if [ "$arm" = cached ]; then
    k -n "$NS" get pod "$baseline" -o json >"$dir/single-pod.json"
    k -n "$NS" logs "$baseline" -c mnemo-warmup-fetch >"$dir/single.fetch.log" 2>&1 || true
    k -n "$NS" logs "$baseline" -c app >"$dir/single.app.log" 2>&1 || true
  fi
  python3 -c 'import datetime; print(datetime.datetime.now(datetime.timezone.utc).isoformat())' >"$dir/scale-requested-at.txt"
  if [ "$arm" = cached ]; then
    rx_before=$(node_rx_bytes); start_ns=$(now_ns)
  fi
  k -n "$NS" scale deploy/"$arm" --replicas=10 >/dev/null
  if ! k -n "$NS" rollout status deploy/"$arm" --timeout=900s >"$dir/rollout.log" 2>&1; then
    k -n "$NS" get pods -l "app=$arm" -o json >"$dir/failure-pods.json" || true
    echo "Burst failed; see $dir" >&2; return 1
  fi
  if [ "$arm" = cached ]; then
    rx_after=$(node_rx_bytes); end_ns=$(now_ns)
    printf 'concurrent-9,%s,%s,%s\n' "$rx_before" "$rx_after" "$((end_ns-start_ns))" >>"$dir/network.csv"
  fi
  k -n "$NS" get pods -l "app=$arm" -o json >"$dir/pods.json"
  for pod in $(k -n "$NS" get pods -l "app=$arm" -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'); do
    [ "$pod" != "$baseline" ] || continue
    k -n "$NS" logs "$pod" -c mnemo-warmup-fetch >"$dir/$pod.fetch.log" 2>&1 || true
    k -n "$NS" logs "$pod" -c app >"$dir/$pod.app.log" 2>&1 || true
    k -n "$NS" exec "$pod" -c app -- cat /sys/fs/cgroup/cpu.stat >"$dir/$pod.cpu.stat" 2>/dev/null || true
    k -n "$NS" exec "$pod" -c app -- sh -c 'wc -c /mnemo-cache/* 2>/dev/null || true' >"$dir/$pod.archive-sizes.txt" 2>/dev/null || true
  done
  k -n "$NS" scale deploy/"$arm" --replicas=1 >/dev/null
  k -n "$NS" rollout status deploy/"$arm" --timeout=600s >/dev/null
  for attempt in $(seq 1 180); do
    [ "$(k -n "$NS" get pods -l "app=$arm" --no-headers | wc -l | tr -d ' ')" = 1 ] && return 0
    sleep 1
  done
  echo "Timed out waiting for scaled-down $arm pods" >&2; return 1
}
for round in $(seq 1 "$ROUNDS"); do
  echo "Burst $round/$ROUNDS"
  case $((round % 3)) in
    1) arms=(none cached fault) ;;
    2) arms=(cached fault none) ;;
    0) arms=(fault none cached) ;;
  esac
  for arm in "${arms[@]}"; do echo "  $arm"; burst "$arm" "$round"; done
done
python3 scripts/bench_env.py "$CTX" "$CLUSTER-control-plane" "$OUT/environment.json" \
  "study=remote-registry-burst" "store=$STORE" "rounds=$ROUNDS" "replicas_before=1" "replicas_after=10" "slow_local=$SLOW" \
  "app_cpu_request=100m" "app_memory_request=256Mi" "app_cpu_limit=1" "app_memory_limit=512Mi" \
  "readiness_initial_delay_seconds=0" "readiness_period_seconds=$PROBE_PERIOD" \
  "jdk=$(docker run --rm --entrypoint java "$APP_IMAGE" -version 2>&1 | head -1)" \
  "date=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
python3 scripts/bench_burst_summarize.py "$OUT" | tee "$OUT/summary.md"
echo "Results in $OUT"
