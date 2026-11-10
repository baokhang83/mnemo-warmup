#!/usr/bin/env bash
# End-to-end test of the whole chain on a real Kubernetes cluster (kind), for each JDK given (default: 21 25):
#
#   1. builds the tools image and the example Spring Boot image (extracted at build time) for that JDK,
#   2. starts a kind cluster and a registry on the kind network (the archive store),
#   3. installs examples/helm/example-app (a Deployment that uses the mnemo-warmup chart) and asserts the app
#      starts WITHOUT an archive first,
#   4. runs the trainer CronJob's job and asserts it trains and uploads (and releases its Lease), then runs it again
#      and asserts ALREADY_STORED,
#   5. restarts the Deployment and asserts: fetch found the archive, the app's classes load from it (AppCDS on JDK 21,
#      AOT on JDK 25), and java is PID 1,
#   6. changes a JVM option only in the Deployment's command and asserts the pod still starts, uncached, with a logged reason.
#
# Usage: scripts/e2e-kind.sh [jdk ...]       Needs docker, kind, kubectl, helm and network access (Maven Central, image pulls).
# KEEP=1 keeps the cluster and registry for debugging. Logs of a failed run are written to $ARTIFACTS.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2
JDKS=("$@"); [ ${#JDKS[@]} -gt 0 ] || JDKS=(21 25)
RUN="$$-$(date +%s)"
CLUSTER="mw-e2e-$RUN"
CTX="kind-$CLUSTER"
REG="mw-e2e-registry-$RUN"
TOOLS_TAG="e2e-$RUN"
ARTIFACTS="${ARTIFACTS:-/tmp/mw-e2e-$RUN}"
WORK=$(mktemp -d)   # a throw-away copy of the charts: dependency builds must not touch the checkout
failures=0
IMAGES=()

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

die() { # <namespace> <message>
  echo "  FATAL: $2"; dump "$1"; failures=$((failures + 1)); summary; exit 1
}

summary() {
  if [ "$failures" -eq 0 ]; then echo "ALL OK"; return; fi
  echo "$failures FAILED"
  for n in "${JDKS[@]}"; do [ -f "$ARTIFACTS/dump-e2e-$n.txt" ] || dump "e2e-$n" 2>/dev/null || true; done
}

cleanup() {
  rm -rf "$WORK"
  if [ "${KEEP:-}" = 1 ]; then echo "KEEP=1: cluster $CLUSTER and registry $REG left running (context $CTX)"; return; fi
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
  docker rm -f "$REG" >/dev/null 2>&1 || true
  for i in ${IMAGES[@]+"${IMAGES[@]}"}; do docker image rm -f "$i" >/dev/null 2>&1 || true; done
}
trap cleanup EXIT

for tool in docker kind kubectl helm; do command -v "$tool" >/dev/null || { echo "missing: $tool"; exit 2; }; done

echo "== building images"
TOOLS_IMAGE="mnemo-warmup-tools:$TOOLS_TAG"
docker build -q -f image/Dockerfile -t "$TOOLS_IMAGE" --build-arg VERSION=e2e . >/dev/null || { echo "tools image build failed"; exit 1; }
IMAGES+=("$TOOLS_IMAGE")
for n in "${JDKS[@]}"; do
  docker build -q --build-arg "JDK=$n" -t "mnemo-example:$n-$RUN" examples/spring-boot >/dev/null || { echo "example image build failed for JDK $n"; exit 1; }
  IMAGES+=("mnemo-example:$n-$RUN")
done

echo "== starting kind cluster $CLUSTER and the archive registry"
kind create cluster --name "$CLUSTER" ${KIND_NODE_IMAGE:+--image "$KIND_NODE_IMAGE"} --wait 180s >/dev/null 2>&1 || { echo "kind create failed"; exit 1; }
docker run -d --name "$REG" --network kind registry:2 >/dev/null || { echo "registry start failed"; exit 1; }
REG_IP=$(docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' "$REG")
[ -n "$REG_IP" ] || { echo "no registry address on the kind network"; exit 1; }
for img in "${IMAGES[@]}"; do kind load docker-image "$img" --name "$CLUSTER" >/dev/null 2>&1 || { echo "kind load failed for $img"; exit 1; }; done
mkdir -p "$WORK/deploy" "$WORK/examples"
cp -R deploy/helm "$WORK/deploy/helm"
cp -R examples/helm "$WORK/examples/helm"
CHART="$WORK/examples/helm/example-app"
helm dependency build "$CHART" >/dev/null 2>&1 || { echo "helm dependency build failed"; exit 1; }

for n in "${JDKS[@]}"; do
  ns="e2e-$n"
  echo "== JDK $n (namespace $ns)"
  k create namespace "$ns" >/dev/null
  if [ "$n" -ge 25 ]; then expect_type=LEYDEN_AOT; else expect_type=APP_CDS; fi

  cat >"$ARTIFACTS.values-$n.yaml" <<YAML
javaOptions: ["-Xlog:class+load:file=/tmp/classload.log"]
warmup:
  app:
    image: { repository: mnemo-example, tag: "$n-$RUN", pullPolicy: Never }
  tools:
    image: { repository: mnemo-warmup-tools, tag: "$TOOLS_TAG", pullPolicy: Never }
  store: { url: "oci://$REG_IP:5000/mnemo/example-$n", plainHttp: true }
  trainer:
    suspend: true            # never fires on its own: the test starts the job itself (a suspended CronJob can still be run by hand)
    timeoutSeconds: 300
YAML
  cat >"$ARTIFACTS.fallback-$n.yaml" <<YAML
# an option that changes archive validity, added ONLY to the Deployment's java command (not to warmup.app.jvmArgs)
javaOptions: ["-Xlog:class+load:file=/tmp/classload.log", "-Xmx256m"]
YAML

  # an aliased subchart names its resources after the alias, so find the CronJob by its release label
  cronjob() { k -n "$ns" get cronjob -l app.kubernetes.io/instance=ex -o name | head -1 | sed 's|.*/||'; }
  # Running pods that are NOT being deleted. During a rollout the old pods stay phase Running while they terminate; picking
  # one of them reads the previous generation's logs (no archive) and then fails to exec (found on the slower CI runner).
  app_pods() {
    k -n "$ns" get pods -l app=ex-app -o jsonpath='{range .items[?(@.status.phase=="Running")]}{.metadata.name}{"\t"}{.metadata.deletionTimestamp}{"\n"}{end}' \
      | awk -F'\t' '$2 == "" { print "pod/" $1 }'
  }
  # waits until the rollout has fully replaced the old pods: only live, ready pods remain, as many as the replicas
  settle() {
    local want=2 i
    for i in $(seq 1 90); do
      if [ "$(k -n "$ns" get pods -l app=ex-app -o name | wc -l | tr -d ' ')" = "$want" ] \
         && [ -z "$(k -n "$ns" get pods -l app=ex-app -o jsonpath='{.items[*].metadata.deletionTimestamp}')" ] \
         && [ "$(k -n "$ns" get pods -l app=ex-app -o jsonpath='{.items[*].status.containerStatuses[*].ready}' | tr ' ' '\n' | grep -c true)" = "$want" ]; then
        return 0
      fi
      sleep 2
    done
    die "$ns" "the rollout did not settle (old pods still terminating or new pods not ready)"
  }
  # Prints the class-load line of the application's own class. It FAILS (and prints nothing) if the exec, the log or the
  # line is missing, so a check can never pass just because it could not look.
  source_line() { k -n "$ns" exec "$1" -c app -- grep -m1 "DemoApplication source:" /tmp/classload.log 2>/dev/null; }

  # ---- A. first start: no archive yet ----
  helm --kube-context "$CTX" install ex "$CHART" -n "$ns" -f "$ARTIFACTS.values-$n.yaml" --wait --timeout 6m >/dev/null 2>&1 \
    || die "$ns" "helm install did not become ready (the app must start without an archive)"
  ok "the app starts and becomes ready without an archive"
  pod=$(app_pods | head -1)
  fetch_log=$(k -n "$ns" logs "$pod" -c mnemo-warmup-fetch 2>&1)
  if has "$fetch_log" "No usable archive"; then ok "fetch reports no usable archive and exits 0"; else fail "fetch log: $fetch_log"; fi
  if line=$(source_line "$pod") && has "$line" "DemoApplication source: file:"; then ok "app classes load from the JAR (no archive)"; else fail "expected the class to load from the JAR, got: '${line:-<no class-load line>}'"; fi

  # ---- B. the trainer: trains once ----
  k -n "$ns" create job t1 --from="cronjob/$(cronjob)" >/dev/null || die "$ns" "cannot create the training job"
  k -n "$ns" wait --for=condition=complete job/t1 --timeout=600s >/dev/null 2>&1 || die "$ns" "the training job did not complete"
  train_log=$(k -n "$ns" logs job/t1 -c trainer 2>&1)
  if has "$train_log" "train: TRAINED"; then ok "the trainer trained and uploaded ($expect_type)"; else fail "trainer log: $train_log"; fi
  if has "$train_log" "Training $expect_type"; then ok "it trained the cache type that fits JDK $n"; else fail "expected 'Training $expect_type' in: $train_log"; fi
  if [ -z "$(k -n "$ns" get leases -o name 2>/dev/null | grep mnemo-warmup || true)" ]; then ok "the Lease was released"; else fail "a Lease was left behind"; fi

  # ---- C. the trainer again: nothing to do ----
  k -n "$ns" create job t2 --from="cronjob/$(cronjob)" >/dev/null || die "$ns" "cannot create the second job"
  k -n "$ns" wait --for=condition=complete job/t2 --timeout=300s >/dev/null 2>&1 || die "$ns" "the second job did not complete"
  second=$(k -n "$ns" logs job/t2 -c trainer 2>&1)
  if has "$second" "ALREADY_STORED" && ! has "$second" "Training "; then ok "a second run finds a complete entry and does not train"; else fail "second run: $second"; fi

  # ---- D. new pods use the archive ----
  k -n "$ns" rollout restart deploy/ex-app >/dev/null
  k -n "$ns" rollout status deploy/ex-app --timeout=300s >/dev/null 2>&1 || die "$ns" "the restarted Deployment did not become ready"
  settle
  pod=$(app_pods | head -1)
  [ -n "$pod" ] || die "$ns" "no live application pod after the restart"
  fetch_log=$(k -n "$ns" logs "$pod" -c mnemo-warmup-fetch 2>&1)
  if has "$fetch_log" "($expect_type)"; then ok "fetch found the $expect_type archive"; else fail "fetch log: $fetch_log"; fi
  app_log=$(k -n "$ns" logs "$pod" -c app 2>&1)
  if has "$app_log" "run: using $expect_type archive"; then ok "run validated the archive for this launch"; else fail "app log: $(grep 'run:' <<<"$app_log")"; fi
  if line=$(source_line "$pod") && has "$line" "DemoApplication source: shared objects file"; then ok "the application's own class loads from the archive"; else fail "expected the class to load from the archive, got: '${line:-<no class-load line>}'"; fi
  cmdline=$(k -n "$ns" exec "$pod" -c app -- sh -c 'tr "\0" " " </proc/1/cmdline' 2>&1)
  if has "$cmdline" "java" && ! has "$cmdline" "mnemo-warmup"; then ok "java is PID 1 (no wrapper process)"; else fail "PID 1 is: $cmdline"; fi
  if has "$cmdline" "$( [ "$expect_type" = LEYDEN_AOT ] && echo -XX:AOTCache= || echo -XX:SharedArchiveFile= )"; then ok "the archive flag is on the command line"; else fail "command line: $cmdline"; fi

  # ---- E. a changed JVM option: still starts, uncached, with a reason ----
  helm --kube-context "$CTX" upgrade ex "$CHART" -n "$ns" -f "$ARTIFACTS.values-$n.yaml" -f "$ARTIFACTS.fallback-$n.yaml" --wait --timeout 6m >/dev/null 2>&1 \
    || die "$ns" "the Deployment with a changed JVM option did not become ready"
  settle
  pod=$(app_pods | head -1)
  app_log=$(k -n "$ns" logs "$pod" -c app 2>&1)
  if has "$app_log" "run: archive does not match this launch"; then ok "a changed -Xmx falls back with a logged reason"; else fail "app log: $(grep 'run:' <<<"$app_log")"; fi
  if line=$(source_line "$pod") && has "$line" "DemoApplication source: file:"; then ok "and the pod runs uncached but healthy"; else fail "expected the class to load from the JAR after the mismatch, got: '${line:-<no class-load line>}'"; fi
done

rm -f "$ARTIFACTS".values-*.yaml "$ARTIFACTS".fallback-*.yaml
summary
[ "$failures" -eq 0 ]
