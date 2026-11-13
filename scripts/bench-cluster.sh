#!/usr/bin/env bash
# Pod-to-Ready breakdown on a kind cluster: how long does a COLD pod take to become Ready, split into phases, with and without
# mnemo-warmup? Three arms run the same application image, interleaved so drift in the machine hits all of them equally:
#
#   none      the plain Deployment, no mnemo-warmup at all
#   nocache   mnemo-warmup on, but the archive repository is empty: the cost of the machinery with no benefit
#   cached    mnemo-warmup on and an archive trained for this image: what a real pod does
#
# Each measurement scales one Deployment from 0 to 1 (a brand-new pod; the image is already on the node, so image pulls are NOT
# measured), waits for Ready, records the pod's own timestamps and the tools' timing lines (MNEMO_WARMUP_TIMING=1), and scales
# back to 0. One discarded warm-up round per arm comes first. Raw pod JSON and logs are kept; scripts/bench_summarize.py turns
# them into the tables. See benchmarks/README.md for the method, its limits and the results.
#
# Usage: scripts/bench-cluster.sh [--app spring-heavy|example|jenkins|sonarqube] [--jdk 21|25] [--restarts N] [--out DIR] [--app-cpu CPUS] [--app-memory MEM] [--tools-delivery init-container|image-volume]
#   --app-cpu / --app-memory set requests = limits on the APPLICATION container (for example 1 and 1Gi, which makes the pod
#   Guaranteed). The default is no pod limits (BestEffort). The generated fetch init container never gets limits from the injector.
#   Needs docker, kind, kubectl, helm 3, java 21+, python3 and network access. KEEP=1 keeps the cluster and registry.
# Besides the measurements it records the machine and cluster it ran on (environment.json) and the latency of the archive
# registry as seen from the node (registry.txt), so a result can be compared with another run or checked.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2
ROOT=$(pwd)
APP=spring-heavy
JDK=21
RESTARTS=12
APP_CPU=""
APP_MEMORY=""
TOOLS_DELIVERY=init-container
RUN="$$-$(date +%s)"
OUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --app) APP=$2; shift 2 ;;
    --jdk) JDK=$2; shift 2 ;;
    --restarts) RESTARTS=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --app-cpu) APP_CPU=$2; shift 2 ;;
    --app-memory) APP_MEMORY=$2; shift 2 ;;
    --tools-delivery) TOOLS_DELIVERY=$2; shift 2 ;;
    *) echo "unknown option: $1"; exit 2 ;;
  esac
done
[ -n "$OUT" ] || OUT="benchmarks/results/$(date +%Y%m%d-%H%M%S)-$APP"
mkdir -p "$OUT/raw"
OUT=$(cd "$OUT" && pwd)

case "$JDK" in 21|25) ;; *) echo "unsupported --jdk: $JDK (use 21 or 25)"; exit 2 ;; esac
if [ "$JDK" != 21 ] && [ "$APP" != spring-heavy ] && [ "$APP" != example ]; then
  echo "--jdk is supported only for spring-heavy and example"; exit 2
fi
case "$APP" in
  spring-heavy) APP_DIR=benchmarks/spring-heavy;  APP_IMAGE="bench-spring-heavy:$RUN"; READY_PATH=/actuator/health/readiness; BUILD_ARGS=(--build-arg JDK="$JDK") ;;
  example)      APP_DIR=examples/spring-boot; APP_IMAGE="mnemo-example:bench-$RUN"; READY_PATH=/health;                    BUILD_ARGS=(--build-arg JDK="$JDK") ;;
  # Jenkins LTS WAR (third party, not Spring Boot): trained with http-probe on /login, which answers 200 only once it is up.
  jenkins)      APP_DIR=benchmarks/jenkins;      APP_IMAGE="bench-jenkins:$RUN";      READY_PATH=/login; BUILD_ARGS=(--build-arg JDK=21)
                APP_JAR=/app/jenkins.war; APP_JAVA_OPTS='["-Djenkins.install.runSetupWizard=false"]'
                WARMUP_SETS=(--set warmup.exitMode=http-probe --set warmup.probeUrl=http://localhost:8080/login) ;;
  # SonarQube Community Build (official image, pinned): a launcher JVM that starts web, compute-engine and search JVMs, with a
  # PostgreSQL started here. Its status endpoint answers 200 while still STARTING, so readiness is a command that wants "UP".
  sonarqube)    APP_DIR=benchmarks/sonarqube;    APP_IMAGE="bench-sonarqube:$RUN";    READY_PATH=/api/system/status; BUILD_ARGS=()
                APP_ROOT=/opt/sonarqube; APP_JAR=/opt/sonarqube/lib/sonarqube.jar; APP_JAVA_OPTS='[]'; POSTGRES_IMAGE=postgres:17-alpine
                WARMUP_SETS=(--set warmup.exitMode=http-probe --set warmup.probeUrl=http://localhost:9000/api/system/status --set port=9000)
                APP_VALUES_YAML=$'readinessCommand:\n  - sh\n  - -c\n  - curl -sf http://localhost:9000/api/system/status | grep -q \'"status":"UP"\'\n'
                APP_ENV_YAML=$'  - { name: SONAR_JDBC_URL, value: "jdbc:postgresql://postgres:5432/sonar" }\n  - { name: SONAR_JDBC_USERNAME, value: sonar }\n  - { name: SONAR_JDBC_PASSWORD, value: sonar }\n  - { name: SONAR_LOG_CONSOLE, value: "true" }' ;;
  *) echo "unknown app: $APP"; exit 2 ;;
esac
case "$TOOLS_DELIVERY" in init-container) ;; image-volume) WARMUP_SETS+=(--set warmup.toolsDelivery=image-volume) ;; *) echo "unknown --tools-delivery: $TOOLS_DELIVERY"; exit 2 ;; esac
APP_ROOT=${APP_ROOT:-/app}; APP_VALUES_YAML=${APP_VALUES_YAML:-}; APP_ENV_YAML=${APP_ENV_YAML:-}; POSTGRES_IMAGE=${POSTGRES_IMAGE:-}
APP_JAR=${APP_JAR:-/app/app.jar}; APP_JAVA_OPTS=${APP_JAVA_OPTS:-[]}; WARMUP_SETS=("${WARMUP_SETS[@]+"${WARMUP_SETS[@]}"}")

CLUSTER="mw-bench-$RUN"; CTX="kind-$CLUSTER"; REG="mw-bench-registry-$RUN"
TOOLS_IMAGE="mnemo-warmup-tools:bench-$RUN"
NS=bench
BIN=$(mktemp -d)
IMAGES=("$TOOLS_IMAGE" "$APP_IMAGE")
k() { kubectl --context "$CTX" "$@"; }

cleanup() {
  rm -rf "$BIN"
  if [ "${KEEP:-}" = 1 ]; then echo "KEEP=1: cluster $CLUSTER and registry $REG left running (context $CTX)"; return; fi
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
  docker rm -f "$REG" >/dev/null 2>&1 || true
  for i in "${IMAGES[@]}"; do docker image rm -f "$i" >/dev/null 2>&1 || true; done
}
trap cleanup EXIT

for tool in docker kind kubectl helm java python3; do command -v "$tool" >/dev/null || { echo "missing: $tool"; exit 2; }; done
case "$(helm version --short 2>/dev/null)" in v3.*) ;; *) echo "Helm 3 is required"; exit 2 ;; esac

JAR=${MNEMO_WARMUP_JAR:-warmup-cli/target/mnemo-warmup.jar}
# Rebuild when the jar is missing or any source is newer than it: the injector and the trainer in the benchmark must be this checkout's.
if [ ! -f "$JAR" ] || [ -n "$(find warmup-core/src warmup-cli/src pom.xml warmup-core/pom.xml warmup-cli/pom.xml -type f -newer "$JAR" 2>/dev/null | head -1)" ]; then
  echo "== building the CLI jar"
  mvn -B -q -DskipTests -Dmaven.javadoc.skip=true -Djacoco.skip=true -pl warmup-cli -am package >/dev/null 2>&1 || { echo "CLI build failed"; exit 1; }
fi
JAR=$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")
printf '#!/bin/sh\nexec java -jar "%s" "$@"\n' "$JAR" >"$BIN/mnemo-warmup"; chmod +x "$BIN/mnemo-warmup"
export PATH="$BIN:$PATH"

echo "== building images (tools image from this checkout, application: $APP)"
docker build -q -f image/Dockerfile -t "$TOOLS_IMAGE" --build-arg VERSION=bench . >/dev/null || { echo "tools image build failed"; exit 1; }
docker build -q ${BUILD_ARGS[@]+"${BUILD_ARGS[@]}"} -t "$APP_IMAGE" "$APP_DIR" >/dev/null || { echo "application image build failed"; exit 1; }

echo "== starting kind cluster and the archive registry"
kind create cluster --name "$CLUSTER" ${KIND_NODE_IMAGE:+--image "$KIND_NODE_IMAGE"} --wait 180s >/dev/null 2>&1 || { echo "kind create failed"; exit 1; }
docker run -d --name "$REG" --network kind registry:2 >/dev/null || { echo "registry start failed"; exit 1; }
REG_IP=$(docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' "$REG")
for img in "${IMAGES[@]}"; do kind load docker-image "$img" --name "$CLUSTER" >/dev/null 2>&1 || { echo "kind load failed for $img"; exit 1; }; done
k create namespace "$NS" >/dev/null
if [ -n "$POSTGRES_IMAGE" ]; then
  echo "== starting PostgreSQL ($POSTGRES_IMAGE) for the application"
  # The node pulls the image itself: a freshly pulled multi-platform image does not always survive "kind load".
  k -n "$NS" apply -f - >/dev/null <<YAML
apiVersion: apps/v1
kind: Deployment
metadata: { name: postgres }
spec:
  replicas: 1
  selector: { matchLabels: { app: postgres } }
  template:
    metadata: { labels: { app: postgres } }
    spec:
      containers:
        - name: postgres
          image: $POSTGRES_IMAGE
          imagePullPolicy: IfNotPresent
          env:
            - { name: POSTGRES_USER, value: sonar }
            - { name: POSTGRES_PASSWORD, value: sonar }
            - { name: POSTGRES_DB, value: sonar }
          ports: [{ containerPort: 5432 }]
          readinessProbe: { exec: { command: ["pg_isready", "-U", "sonar"] }, periodSeconds: 2 }
---
apiVersion: v1
kind: Service
metadata: { name: postgres }
spec:
  selector: { app: postgres }
  ports: [{ port: 5432 }]
YAML
  k -n "$NS" rollout status deploy/postgres --timeout=600s >/dev/null || { echo "PostgreSQL did not become ready (the node must be able to pull $POSTGRES_IMAGE)"; exit 1; }
fi

# ---- three releases of the same chart, one per arm ----
VALUES="$BIN/values.yaml"
cat >"$VALUES" <<YAML
image: { repository: ${APP_IMAGE%%:*}, tag: "${APP_IMAGE#*:}", pullPolicy: Never }
readinessPath: $READY_PATH
jar: $APP_JAR
javaOptions: $APP_JAVA_OPTS
extraEnv:
  - { name: MNEMO_WARMUP_TIMING, value: "1" }
${APP_ENV_YAML}
${APP_VALUES_YAML}
YAML
if [ -n "$APP_CPU$APP_MEMORY" ]; then
  {
    echo "resources:"
    echo "  requests: { cpu: \"${APP_CPU:-0}\", memory: \"${APP_MEMORY:-0}\" }"
    echo "  limits: { cpu: \"${APP_CPU:-0}\", memory: \"${APP_MEMORY:-0}\" }"
  } >>"$VALUES"
fi

deploy() { # <release> <extra helm args...>
  local rel=$1; shift
  helm --kube-context "$CTX" upgrade --install "$rel" examples/annotated-app -n "$NS" -f "$VALUES" \
    --post-renderer "$ROOT/scripts/mnemo-warmup-inject" \
    --post-renderer-args="--namespace=$NS" --post-renderer-args="--tools-image=$TOOLS_IMAGE" ${WARMUP_SETS[@]+"${WARMUP_SETS[@]}"} "$@" >/dev/null 2>&1
}
quiesce() { # <release>: scale to 0 and keep the trainer from running on its own
  k -n "$NS" scale deploy/"$1" --replicas=0 >/dev/null
  local cj
  for cj in $(k -n "$NS" get cronjob -l warmup.mnemo.io/owner="$1" -o name 2>/dev/null); do
    k -n "$NS" patch "$cj" -p '{"spec":{"suspend":true}}' >/dev/null 2>&1 || true
  done
}

echo "== deploying the three arms"
deploy none    --set warmup.enabled=false --set replicas=1 || { echo "deploy none failed"; exit 1; }
deploy nocache --set warmup.store="oci://$REG_IP:5000/bench/nocache" --set warmup.plainHttp=true || { echo "deploy nocache failed"; exit 1; }
quiesce nocache
deploy cached  --set warmup.store="oci://$REG_IP:5000/bench/cached" --set warmup.plainHttp=true || { echo "deploy cached failed"; exit 1; }
quiesce cached
quiesce none

echo "== training the archive for the cached arm"
cron=$(k -n "$NS" get cronjob -l warmup.mnemo.io/owner=cached -o name | head -1 | sed 's|.*/||')
k -n "$NS" create job train --from="cronjob/$cron" >/dev/null || { echo "cannot start the training job"; exit 1; }
k -n "$NS" wait --for=condition=complete job/train --timeout=900s >/dev/null 2>&1 || { echo "the training job did not complete"; k -n "$NS" logs job/train -c trainer | tail -20; exit 1; }
k -n "$NS" logs job/train -c trainer >"$OUT/raw/train.log" 2>&1
mkdir -p "$OUT/raw/training"
k -n "$NS" get job train -o json >"$OUT/raw/training/job.json"
k -n "$NS" get pods -l job-name=train -o json >"$OUT/raw/training/pods.json"
grep -q "train: TRAINED" "$OUT/raw/train.log" || { echo "training did not report TRAINED; the end of the trainer log:"; tail -25 "$OUT/raw/train.log" | cut -c1-220; exit 1; }
ARCHIVE_BYTES=$(docker exec "$REG" sh -c 'find /var/lib/registry -path "*blobs*" -name data -size +100k -exec wc -c {} \;' | sort -n | tail -1 | awk '{print $1}')

# ---- measurement ----
measure() { # <arm> <index or warmup>
  local arm=$1 tag=$2
  k -n "$NS" scale deploy/"$arm" --replicas=1 >/dev/null
  local pod="" i
  for i in $(seq 1 600); do
    pod=$(k -n "$NS" get pods -l app="$arm" -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.metadata.deletionTimestamp}{"\t"}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2>/dev/null \
      | awk -F'\t' '$2 == "" && $3 == "True" { print $1; exit }')
    [ -n "$pod" ] && break
    sleep 1
  done
  [ -n "$pod" ] || { echo "  no Ready pod for $arm"; return 1; }
  local base="$OUT/raw/$arm-$tag"
  k -n "$NS" get pod "$pod" -o json >"$base.json"
  k -n "$NS" logs "$pod" -c mnemo-warmup-fetch >"$base.fetch.log" 2>&1 || : >"$base.fetch.log"
  k -n "$NS" logs "$pod" -c app >"$base.app.log" 2>&1
  # with tools-delivery=image-volume there is no fetch container: the fetch ran in the application container and logged there
  [ -s "$base.fetch.log" ] || cp "$base.app.log" "$base.fetch.log"
  k -n "$NS" scale deploy/"$arm" --replicas=0 >/dev/null
  for i in $(seq 1 120); do
    [ -z "$(k -n "$NS" get pods -l app="$arm" -o name 2>/dev/null)" ] && break
    sleep 1
  done
  [ -z "$(k -n "$NS" get pods -l app="$arm" -o name 2>/dev/null)" ] || { echo "  pod of $arm did not terminate"; return 1; }
  return 0
}

echo "== warm-up round (discarded)"
for arm in none nocache cached; do measure "$arm" warmup || exit 1; done
echo "== $RESTARTS measured rounds, interleaved"
for n in $(seq 1 "$RESTARTS"); do
  printf '  round %s/%s' "$n" "$RESTARTS"
  for arm in none nocache cached; do
    measure "$arm" "$(printf '%02d' "$n")" || exit 1
  done
  echo
done

# ---- the machine, the cluster and the registry, so the numbers can be compared or checked ----
NODE="$CLUSTER-control-plane"
echo "== measuring registry latency from the node and recording the environment"
TAG=$(docker exec "$NODE" curl -s "http://$REG_IP:5000/v2/bench/cached/tags/list" | python3 -c 'import json,sys; print(json.load(sys.stdin)["tags"][0])')
LAYER=$(docker exec "$NODE" curl -s -H 'Accept: application/vnd.oci.image.manifest.v1+json' "http://$REG_IP:5000/v2/bench/cached/manifests/$TAG" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["layers"][0]["digest"])')
{
  echo "# from the kind node to the archive registry ($REG_IP:5000): request time of GET /v2/ (ms), then archive blob downloads"
  for _ in $(seq 1 30); do
    docker exec "$NODE" curl -s -o /dev/null -w '%{time_total}\n' "http://$REG_IP:5000/v2/" | awk '{ printf "rtt_ms %.2f\n", $1 * 1000 }'
  done
  for _ in 1 2 3 4 5; do
    docker exec "$NODE" curl -s -o /dev/null -w 'download_bytes %{size_download} seconds %{time_total}\n' "http://$REG_IP:5000/v2/bench/cached/blobs/$LAYER"
  done
} >"$OUT/registry.txt"

python3 scripts/bench_env.py "$CTX" "$NODE" "$OUT/environment.json" \
  "app=$APP" "app_dir=$APP_DIR" "restarts=$RESTARTS" "archive_bytes=${ARCHIVE_BYTES:-unknown}" \
  "app_cpu_limit=${APP_CPU:-none}" "app_memory_limit=${APP_MEMORY:-none}" \
  "jdk=$(docker run --rm --entrypoint java "$APP_IMAGE" -version 2>&1 | head -1)" \
  "app_jars=$(docker run --rm --entrypoint sh "$APP_IMAGE" -c "find $APP_ROOT \\( -name '*.jar' -o -name '*.war' \\) | wc -l" | tr -d ' ')" \
  "app_kib=$(docker run --rm --entrypoint sh "$APP_IMAGE" -c "du -sk $APP_ROOT | cut -f1" | tr -d ' ')" \
  "date=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  "tools_delivery=$TOOLS_DELIVERY" \
  "command=scripts/bench-cluster.sh --app $APP --jdk $JDK --restarts $RESTARTS${APP_CPU:+ --app-cpu $APP_CPU}${APP_MEMORY:+ --app-memory $APP_MEMORY}${TOOLS_DELIVERY:+ --tools-delivery $TOOLS_DELIVERY}"

python3 scripts/bench_summarize.py "$OUT" | tee "$OUT/summary.md" || exit 1
echo "results in $OUT"
