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
# TOOLS_DELIVERY=init-container (default) or image-volume (docs/adr/0012: no init containers, the tools are an image volume and
# the fetch runs in the application container) selects the chart's tools.delivery; the same scenarios run in both modes.
# WITH_PULL_SECRET=1 starts the registry with htpasswd authentication and gives the chart that login as store.pullSecret and
# store.pushSecret. Nothing can then fetch or push without the Secret, so the run proves the Secret reaches the fetch and the
# trainer, and (with image-volume, where the fetch stays in one isolated init container) that the application container
# never sees it.
# WITH_SIGNING=1 adds the signed-archive scenario (docs/adr/0013): with an unsigned archive already in use, turn signing on and
# watch the pod refuse it and still start; the trainer re-publishes it signed and the pod uses it; then give the pod the WRONG
# public key and watch it refuse the archive and still start; finally run the trainer as a NON-ROOT user (it reads the key through
# fsGroup) and rotate the key: nothing is re-published until the old key leaves the list, then the entry moves to the new key. Needs no Java on this machine: keys are made with the example image's JDK.
# Usage: scripts/e2e-kind.sh [jdk ...]       Needs docker, kind, kubectl, helm and network access (Maven Central, image pulls).
# KEEP=1 keeps the cluster and registry for debugging. Logs of a failed run are written to $ARTIFACTS.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2
JDKS=("$@"); [ ${#JDKS[@]} -gt 0 ] || JDKS=(21 25)
TOOLS_DELIVERY=${TOOLS_DELIVERY:-init-container}
case "$TOOLS_DELIVERY" in init-container|image-volume) ;; *) echo "TOOLS_DELIVERY must be init-container or image-volume"; exit 2 ;; esac
WITH_PULL_SECRET=${WITH_PULL_SECRET:-0}
case "$WITH_PULL_SECRET" in 0|1) ;; *) echo "WITH_PULL_SECRET must be 0 or 1"; exit 2 ;; esac
# the fetch runs inside the application container only with an image volume and no pull Secret
INLINE_FETCH=0; [ "$TOOLS_DELIVERY" = image-volume ] && [ "$WITH_PULL_SECRET" = 0 ] && INLINE_FETCH=1
REG_USER=mnemo; REG_PASS=e2e-$RANDOM$RANDOM
WITH_SIGNING=${WITH_SIGNING:-0}
case "$WITH_SIGNING" in 0|1) ;; *) echo "WITH_SIGNING must be 0 or 1"; exit 2 ;; esac
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
if [ "$WITH_PULL_SECRET" = 1 ]; then
  docker run --rm --entrypoint htpasswd httpd:2 -Bbn "$REG_USER" "$REG_PASS" >"$WORK/htpasswd" 2>/dev/null || { echo "htpasswd failed"; exit 1; }
  docker run -d --name "$REG" --network kind -v "$WORK/htpasswd:/auth/htpasswd:ro" -e REGISTRY_AUTH=htpasswd \
    -e REGISTRY_AUTH_HTPASSWD_REALM=e2e -e REGISTRY_AUTH_HTPASSWD_PATH=/auth/htpasswd registry:2 >/dev/null || { echo "registry start failed"; exit 1; }
else
  docker run -d --name "$REG" --network kind registry:2 >/dev/null || { echo "registry start failed"; exit 1; }
fi
REG_IP=$(docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' "$REG")
[ -n "$REG_IP" ] || { echo "no registry address on the kind network"; exit 1; }
for img in "${IMAGES[@]}"; do kind load docker-image "$img" --name "$CLUSTER" >/dev/null 2>&1 || { echo "kind load failed for $img"; exit 1; }; done
if [ "$WITH_SIGNING" = 1 ]; then
  # the CLI jar comes out of the tools image; keygen runs in a container that has a JDK (no Java needed on this machine)
  mkdir -p "$WORK/cli" "$WORK/keys"
  cid=$(docker create "$TOOLS_IMAGE" true) || { echo "cannot read the tools image"; exit 1; }
  docker cp "$cid:/opt/mnemo-warmup/mnemo-warmup.jar" "$WORK/cli/mnemo-warmup.jar" >/dev/null || { docker rm "$cid" >/dev/null; echo "no CLI jar in the tools image"; exit 1; }
  docker rm "$cid" >/dev/null
fi
mkdir -p "$WORK/deploy" "$WORK/examples"
cp -R deploy/helm "$WORK/deploy/helm"
cp -R examples/helm "$WORK/examples/helm"
CHART="$WORK/examples/helm/example-app"
helm dependency build "$CHART" >/dev/null 2>&1 || { echo "helm dependency build failed"; exit 1; }

# keygen <jdk> <name>: writes $WORK/keys/<name>.key and prints the public key entry (id=base64)
keygen() {
  docker run --rm --user "$(id -u):$(id -g)" -v "$WORK/cli:/cli:ro" -v "$WORK/keys:/keys" --entrypoint java "mnemo-example:$1-$RUN" \
    -jar /cli/mnemo-warmup.jar keygen --out "/keys/$2.key" 2>/dev/null | sed -n 's/^Public key: *//p'
}

for n in "${JDKS[@]}"; do
  ns="e2e-$n"
  echo "== JDK $n (namespace $ns)"
  k create namespace "$ns" >/dev/null
  # a login Secret (used for pull and push) with a registry that requires it, or an empty override
  if [ "$WITH_PULL_SECRET" = 1 ]; then
    k -n "$ns" create secret docker-registry reg-cred --docker-server="$REG_IP:5000" --docker-username="$REG_USER" --docker-password="$REG_PASS" >/dev/null \
      || die "$ns" "cannot create the registry Secret"
    printf 'warmup:\n  store: { pullSecret: reg-cred, pushSecret: reg-cred }\n' >"$ARTIFACTS.secret-$n.yaml"
  else
    printf '{}\n' >"$ARTIFACTS.secret-$n.yaml"
  fi
  if [ "$n" -ge 25 ]; then expect_type=LEYDEN_AOT; else expect_type=APP_CDS; fi

  cat >"$ARTIFACTS.values-$n.yaml" <<YAML
javaOptions: ["-Xlog:class+load:file=/tmp/classload.log"]
warmup:
  app:
    image: { repository: mnemo-example, tag: "$n-$RUN", pullPolicy: Never }
  tools:
    delivery: $TOOLS_DELIVERY
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

  # fetch's output for a pod: its own init container, or (image-volume) the lines it printed in the application container
  fetch_logs() {
    if [ "$INLINE_FETCH" = 1 ]; then
      k -n "$ns" logs "$1" -c app 2>&1 | grep -E '^(fetch:|Archive:|Flags:|No usable archive|mnemo-launcher:)' || true
    else
      k -n "$ns" logs "$1" -c mnemo-warmup-fetch 2>&1
    fi
  }

  # ---- A. first start: no archive yet ----
  helm --kube-context "$CTX" install ex "$CHART" -n "$ns" -f "$ARTIFACTS.values-$n.yaml" -f "$ARTIFACTS.secret-$n.yaml" --wait --timeout 6m >/dev/null 2>&1 \
    || die "$ns" "helm install did not become ready (the app must start without an archive)"
  ok "the app starts and becomes ready without an archive"
  pod=$(app_pods | head -1)
  fetch_log=$(fetch_logs "$pod")
  if has "$fetch_log" "No usable archive"; then ok "fetch reports no usable archive and exits 0"; else fail "fetch log: $fetch_log"; fi
  init_names=$(k -n "$ns" get pod "${pod#pod/}" -o jsonpath='{.spec.initContainers[*].name}')
  if [ "$TOOLS_DELIVERY" = image-volume ]; then
    if [ "$INLINE_FETCH" = 1 ]; then
      if [ -z "$init_names" ]; then ok "the pod has no init containers (tools.delivery=image-volume)"; else fail "init containers in image-volume mode: $init_names"; fi
    else
      if [ "$init_names" = "mnemo-warmup-fetch" ]; then ok "image-volume with a pull Secret: one isolated fetch init container, no install container"; else fail "init containers: '$init_names'"; fi
    fi
    if [ -n "$(k -n "$ns" get pod "${pod#pod/}" -o jsonpath='{.spec.volumes[?(@.name=="mnemo-tools")].image.reference}')" ]; then ok "the tools are an image volume"; else fail "the tools volume is not an image volume"; fi
  else
    if [ "$init_names" = "mnemo-warmup-install mnemo-warmup-fetch" ]; then ok "the pod has the install and fetch init containers"; else fail "init containers: '$init_names'"; fi
  fi
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
  fetch_log=$(fetch_logs "$pod")
  if has "$fetch_log" "($expect_type)"; then ok "fetch found the $expect_type archive"; else fail "fetch log: $fetch_log"; fi
  app_log=$(k -n "$ns" logs "$pod" -c app 2>&1)
  if has "$app_log" "run: using $expect_type archive"; then ok "run validated the archive for this launch"; else fail "app log: $(grep 'run:' <<<"$app_log")"; fi
  if line=$(source_line "$pod") && has "$line" "DemoApplication source: shared objects file"; then ok "the application's own class loads from the archive"; else fail "expected the class to load from the archive, got: '${line:-<no class-load line>}'"; fi
  cmdline=$(k -n "$ns" exec "$pod" -c app -- sh -c 'tr "\0" " " </proc/1/cmdline' 2>&1)
  if has "$cmdline" "java" && ! has "$cmdline" "mnemo-warmup"; then ok "java is PID 1 (no wrapper process)"; else fail "PID 1 is: $cmdline"; fi
  if has "$cmdline" "$( [ "$expect_type" = LEYDEN_AOT ] && echo -XX:AOTCache= || echo -XX:SharedArchiveFile= )"; then ok "the archive flag is on the command line"; else fail "command line: $cmdline"; fi

  # ---- D2. the registry login (WITH_PULL_SECRET=1) ----
  if [ "$WITH_PULL_SECRET" = 1 ]; then
    anon=$(docker exec "$REG" wget -S -q -O /dev/null http://localhost:5000/v2/ 2>&1 | awk '/HTTP\//{print $2; exit}')
    if [ "$anon" = 401 ]; then ok "the registry refuses anonymous requests, so the archive was fetched and pushed with the Secret"; else fail "an anonymous request returned '$anon', expected 401"; fi
    podname=${pod#pod/}
    app_spec=$(k -n "$ns" get pod "$podname" -o jsonpath='{.spec.containers[?(@.name=="app")]}')
    fetch_spec=$(k -n "$ns" get pod "$podname" -o jsonpath='{.spec.initContainers[?(@.name=="mnemo-warmup-fetch")]}')
    if has "$fetch_spec" "mnemo-registry-pull" && has "$fetch_spec" "MNEMO_WARMUP_REGISTRY_AUTH"; then ok "the fetch init container mounts the Secret"; else fail "fetch container spec: $fetch_spec"; fi
    if ! has "$app_spec" "mnemo-registry-pull" && ! has "$app_spec" "MNEMO_WARMUP_REGISTRY_AUTH"; then ok "the application container neither mounts the Secret nor has its path"; else fail "application container spec: $app_spec"; fi
    if k -n "$ns" exec "$podname" -c app -- sh -c 'test ! -e /var/run/mnemo-warmup/registry/config.json' >/dev/null 2>&1; then ok "the Secret's file does not exist inside the application container"; else fail "the registry login is readable inside the application container"; fi
  fi

  SIGNING_ARGS=()
  # ---- S. signed archives (WITH_SIGNING=1) ----
  if [ "$WITH_SIGNING" = 1 ]; then
    key_a=$(keygen "$n" a); key_b=$(keygen "$n" b)
    if [ -n "$key_a" ] && [ -n "$key_b" ] && [ "${key_a%%=*}" != "${key_b%%=*}" ]; then ok "keygen made two different keys (${key_a%%=*}, ${key_b%%=*})"; else die "$ns" "keygen failed: '$key_a' '$key_b'"; fi
    if [ "$(stat -c %a "$WORK/keys/a.key" 2>/dev/null || stat -f %Lp "$WORK/keys/a.key")" = 600 ]; then ok "the private key file is readable by its owner only"; else fail "private key file mode"; fi
    k -n "$ns" create secret generic sign-a --from-file=signing.key="$WORK/keys/a.key" >/dev/null || die "$ns" "cannot create the signing Secret"
    # sign_values <public keys> <key Secret> <root|nonroot>: nonroot runs the trainer as uid 10001 with an fsGroup, so it reads the
    # root-owned, group-readable (0440) key file through the group
    sign_values() {
      printf 'warmup:\n  signing:\n    publicKeys: "%s"\n    keySecret: %s\n' "$1" "$2" >"$ARTIFACTS.signing-$n.yaml"
      if [ "$3" = nonroot ]; then
        printf '  trainer:\n    securityContext: {runAsUser: 10001, runAsNonRoot: true}\n    podSecurityContext: {fsGroup: 10001}\n' >>"$ARTIFACTS.signing-$n.yaml"
      fi
    }

    # S1. signing turned on while an UNSIGNED archive is the one in the registry: it is refused, the pod still starts uncached
    sign_values "$key_a" sign-a root; SIGNING_ARGS=(-f "$ARTIFACTS.signing-$n.yaml")
    helm --kube-context "$CTX" upgrade ex "$CHART" -n "$ns" -f "$ARTIFACTS.values-$n.yaml" -f "$ARTIFACTS.secret-$n.yaml" "${SIGNING_ARGS[@]}" --wait --timeout 6m >/dev/null 2>&1 \
      || die "$ns" "the Deployment with signing on did not become ready (an unsigned archive must never stop a pod)"
    settle
    pod=$(app_pods | head -1)
    fetch_log=$(fetch_logs "$pod")
    if has "$fetch_log" "not signed"; then ok "an unsigned archive is refused once signing is on"; else fail "fetch log: $fetch_log"; fi
    if line=$(source_line "$pod") && has "$line" "DemoApplication source: file:"; then ok "and the pod is healthy, running without the cache"; else fail "expected an uncached start, got: '${line:-<none>}'"; fi

    # S2. the trainer counts that unsigned entry as incomplete and re-publishes it signed
    k -n "$ns" create job t3 --from="cronjob/$(cronjob)" >/dev/null || die "$ns" "cannot create the signing job"
    k -n "$ns" wait --for=condition=complete job/t3 --timeout=600s >/dev/null 2>&1 || die "$ns" "the signing job did not complete"
    sign_log=$(k -n "$ns" logs job/t3 -c trainer 2>&1)
    if has "$sign_log" "train: TRAINED"; then ok "the trainer re-published the unsigned entry, signed"; else fail "trainer log: $sign_log"; fi
    k -n "$ns" create job t4 --from="cronjob/$(cronjob)" >/dev/null || die "$ns" "cannot create the second signing job"
    k -n "$ns" wait --for=condition=complete job/t4 --timeout=300s >/dev/null 2>&1 || die "$ns" "the second signing job did not complete"
    if has "$(k -n "$ns" logs job/t4 -c trainer 2>&1)" "ALREADY_STORED"; then ok "a signed entry counts as complete"; else fail "second signing run: $(k -n "$ns" logs job/t4 -c trainer 2>&1)"; fi

    # S3. the signed archive is used, and the evidence is on disk
    k -n "$ns" rollout restart deploy/ex-app >/dev/null
    k -n "$ns" rollout status deploy/ex-app --timeout=300s >/dev/null 2>&1 || die "$ns" "the restarted Deployment did not become ready"
    settle
    pod=$(app_pods | head -1)
    if has "$(fetch_logs "$pod")" "($expect_type)"; then ok "the signed $expect_type archive was fetched"; else fail "fetch log: $(fetch_logs "$pod")"; fi
    if line=$(source_line "$pod") && has "$line" "DemoApplication source: shared objects file"; then ok "and the application's own class loads from it"; else fail "expected the class to load from the signed archive, got: '${line:-<none>}'"; fi
    if k -n "$ns" exec "${pod#pod/}" -c app -- sh -c 'test -s /mnemo-cache/mnemo-warmup.signature' >/dev/null 2>&1; then ok "the signature file is next to the archive"; else fail "no mnemo-warmup.signature in the cache directory"; fi
    if k -n "$ns" exec "${pod#pod/}" -c app -- sh -c 'test ! -e /var/run/mnemo-warmup/signing' >/dev/null 2>&1; then ok "the application container has no private key"; else fail "the private key is visible in the application container"; fi

    # S4. the pods are given the WRONG public key: the archive is refused, the pod still starts
    sign_values "$key_b" sign-a root
    helm --kube-context "$CTX" upgrade ex "$CHART" -n "$ns" -f "$ARTIFACTS.values-$n.yaml" -f "$ARTIFACTS.secret-$n.yaml" "${SIGNING_ARGS[@]}" --wait --timeout 6m >/dev/null 2>&1 \
      || die "$ns" "the Deployment with a different trusted key did not become ready"
    settle
    pod=$(app_pods | head -1)
    if has "$(fetch_logs "$pod")" "not trusted"; then ok "an archive signed by a key the pod does not trust is refused"; else fail "fetch log: $(fetch_logs "$pod")"; fi
    if line=$(source_line "$pod") && has "$line" "DemoApplication source: file:"; then ok "and the pod is healthy, running without the cache"; else fail "expected an uncached start, got: '${line:-<none>}'"; fi
    sign_values "$key_a" sign-a root # back to the right key: the next scenarios start from a trusted, signed archive
    helm --kube-context "$CTX" upgrade ex "$CHART" -n "$ns" -f "$ARTIFACTS.values-$n.yaml" -f "$ARTIFACTS.secret-$n.yaml" "${SIGNING_ARGS[@]}" --wait --timeout 6m >/dev/null 2>&1 \
      || die "$ns" "the Deployment with the right key again did not become ready"
    settle

    # S5. a NON-ROOT trainer reads the key through its fsGroup, and the key is ROTATED: new key on the list next to the old one, the
    # trainer switched to it, then the old key dropped. The trainer accepts what any listed key signed, so nothing is re-published until
    # the old key leaves the list.
    k -n "$ns" create secret generic sign-b --from-file=signing.key="$WORK/keys/b.key" >/dev/null || die "$ns" "cannot create the second signing Secret"
    sign_values "$key_a,$key_b" sign-b nonroot
    helm --kube-context "$CTX" upgrade ex "$CHART" -n "$ns" -f "$ARTIFACTS.values-$n.yaml" -f "$ARTIFACTS.secret-$n.yaml" "${SIGNING_ARGS[@]}" --wait --timeout 6m >/dev/null 2>&1 \
      || die "$ns" "the Deployment trusting both keys did not become ready"
    settle
    fsgroup=$(k -n "$ns" get "cronjob/$(cronjob)" -o jsonpath='{.spec.jobTemplate.spec.template.spec.securityContext.fsGroup}')
    runas=$(k -n "$ns" get "cronjob/$(cronjob)" -o jsonpath='{.spec.jobTemplate.spec.template.spec.containers[0].securityContext.runAsUser}')
    if [ "$fsgroup" = 10001 ] && [ "$runas" = 10001 ]; then ok "the trainer pod runs as uid $runas with fsGroup $fsgroup"; else fail "trainer pod: uid '$runas' fsGroup '$fsgroup'"; fi
    k -n "$ns" create job t5 --from="cronjob/$(cronjob)" >/dev/null || die "$ns" "cannot create the non-root trainer job"
    k -n "$ns" wait --for=condition=complete job/t5 --timeout=600s >/dev/null 2>&1 || die "$ns" "the non-root trainer job did not complete (can it read its key?)"
    rot_log=$(k -n "$ns" logs job/t5 -c trainer 2>&1)
    if has "$rot_log" "ALREADY_STORED"; then ok "a non-root trainer read its key, and left the entry signed by the old key alone (it is still trusted)"; else fail "trainer log: $rot_log"; fi
    sign_values "$key_b" sign-b nonroot
    helm --kube-context "$CTX" upgrade ex "$CHART" -n "$ns" -f "$ARTIFACTS.values-$n.yaml" -f "$ARTIFACTS.secret-$n.yaml" "${SIGNING_ARGS[@]}" --wait --timeout 6m >/dev/null 2>&1 \
      || die "$ns" "the Deployment trusting only the new key did not become ready (an archive the old key signed must never stop a pod)"
    settle
    pod=$(app_pods | head -1)
    if has "$(fetch_logs "$pod")" "not trusted"; then ok "with the old key dropped, the pod refuses the archive the old key signed and still starts"; else fail "fetch log: $(fetch_logs "$pod")"; fi
    k -n "$ns" create job t6 --from="cronjob/$(cronjob)" >/dev/null || die "$ns" "cannot create the re-publishing job"
    k -n "$ns" wait --for=condition=complete job/t6 --timeout=600s >/dev/null 2>&1 || die "$ns" "the re-publishing job did not complete"
    if has "$(k -n "$ns" logs job/t6 -c trainer 2>&1)" "train: TRAINED"; then ok "the trainer re-published the entry under the new key"; else fail "t6 log: $(k -n "$ns" logs job/t6 -c trainer 2>&1)"; fi
    k -n "$ns" rollout restart deploy/ex-app >/dev/null
    k -n "$ns" rollout status deploy/ex-app --timeout=300s >/dev/null 2>&1 || die "$ns" "the restarted Deployment did not become ready"
    settle
    pod=$(app_pods | head -1)
    if line=$(source_line "$pod") && has "$line" "DemoApplication source: shared objects file"; then ok "the pod uses the archive signed by the new key"; else fail "expected the archive signed by the new key to be used, got: '${line:-<none>}'"; fi
  fi

  # ---- E. a changed JVM option: still starts, uncached, with a reason ----
  helm --kube-context "$CTX" upgrade ex "$CHART" -n "$ns" -f "$ARTIFACTS.values-$n.yaml" -f "$ARTIFACTS.secret-$n.yaml" ${SIGNING_ARGS[@]+"${SIGNING_ARGS[@]}"} -f "$ARTIFACTS.fallback-$n.yaml" --wait --timeout 6m >/dev/null 2>&1 \
    || die "$ns" "the Deployment with a changed JVM option did not become ready"
  settle
  pod=$(app_pods | head -1)
  app_log=$(k -n "$ns" logs "$pod" -c app 2>&1)
  if has "$app_log" "run: archive does not match this launch"; then ok "a changed -Xmx falls back with a logged reason"; else fail "app log: $(grep 'run:' <<<"$app_log")"; fi
  if line=$(source_line "$pod") && has "$line" "DemoApplication source: file:"; then ok "and the pod runs uncached but healthy"; else fail "expected the class to load from the JAR after the mismatch, got: '${line:-<no class-load line>}'"; fi
done

rm -f "$ARTIFACTS".values-*.yaml "$ARTIFACTS".fallback-*.yaml "$ARTIFACTS".secret-*.yaml "$ARTIFACTS".signing-*.yaml
summary
[ "$failures" -eq 0 ]
