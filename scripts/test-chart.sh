#!/usr/bin/env bash
# Lints and renders the Helm chart with several value sets, checks the output, and renders the parent-chart
# fixture that exercises the reusable templates. With KUBE_CONTEXT=<context> every rendered manifest is also
# validated by that cluster's API server (kubectl apply --dry-run=server); nothing is created.
#
# Usage: scripts/test-chart.sh        Needs helm (and kubectl for KUBE_CONTEXT).
set -euo pipefail
cd "$(dirname "$0")/.."

CHART=deploy/helm/mnemo-warmup
PARENT=deploy/helm/tests/parent
DIGEST="sha256:$(printf 'ab%.0s' $(seq 32))"
BASE=(--set app.image.repository=my-app --set app.image.tag=1.0 --set store.url=oci://registry.example.com/team/warmup)
failures=0

ok()   { echo "  ok:   $1"; }
fail() { echo "  FAIL: $1"; failures=$((failures + 1)); }
has()  { grep -qF -- "$2" <<<"$1"; }                 # has "<text>" "<needle>"
check() { if has "$1" "$2"; then ok "$3"; else fail "$3 (missing: $2)"; fi; }
absent() { if has "$1" "$2"; then fail "$3 (unexpected: $2)"; else ok "$3"; fi; }
validate() { # <name> <manifest>
  [ -n "${KUBE_CONTEXT:-}" ] || return 0
  if kubectl --context "$KUBE_CONTEXT" apply --dry-run=server -n default -f - >/dev/null <<<"$2"; then ok "$1 accepted by the API server"; else fail "$1 rejected by the API server"; fi
}

echo "== lint"
helm lint "$CHART" "${BASE[@]}" >/dev/null && ok "helm lint" || fail "helm lint"

echo "== defaults (tag, anonymous registry)"
out=$(helm template rel "$CHART" -n default "${BASE[@]}")
validate "defaults" "$out"
check "$out" "kind: CronJob" "renders the CronJob"
check "$out" "concurrencyPolicy: Forbid" "concurrencyPolicy is Forbid"
check "$out" 'image: "my-app:1.0"' "trainer runs the application image"
check "$out" "- train" "trainer runs the train command"
check "$out" "resources: [\"leases\"]" "RBAC grants leases"
absent "$out" "helm.sh/hook" "is not a Helm hook"
absent "$out" "--image-digest" "no digest flag without a digest"
absent "$out" "MNEMO_WARMUP_REGISTRY_AUTH" "no registry auth without secrets"
absent "$out" "--plain-http" "https by default"

echo "== digest, secrets, jvm args, env, probe, plain http"
out=$(helm template rel "$CHART" -n default --set app.image.repository=my-app --set app.image.digest="$DIGEST" \
  --set store.url=oci://registry.example.com/team/warmup --set store.plainHttp=true --set store.pushSecret=push \
  --set 'app.jvmArgs={-Xmx512m,-XX:+UseG1GC}' --set 'app.env[0].name=JAVA_TOOL_OPTIONS' --set 'app.env[0].value=-Dx=1' \
  --set trainer.exitMode=http-probe --set trainer.probe.url=http://localhost:8080/health \
  --set 'trainer.probe.warmupUrls={/api/a,/api/b}' --set cacheType=appcds)
validate "full values" "$out"
check "$out" "my-app@$DIGEST" "digest replaces the tag in the image"
check "$out" "--image-digest=$DIGEST" "digest is passed to the tool"
check "$out" "--jvm-arg=-Xmx512m" "jvm args are passed"
check "$out" "--jvm-arg=-XX:+UseG1GC" "every jvm arg is passed"
check "$out" "name: JAVA_TOOL_OPTIONS" "app env reaches the trainer"
check "$out" "--plain-http" "plain http is passed"
check "$out" '- "http-probe"' "exit mode http-probe"
check "$out" "http://localhost:8080/health" "probe url"
check "$out" '- "/api/b"' "every warmup url"
check "$out" '- "appcds"' "cache type"
check "$out" "secretName: \"push\"" "push secret is mounted"

echo "== validation"
if helm template rel "$CHART" --set app.image.repository=x --set app.image.tag=1 >/dev/null 2>&1; then fail "store.url is required"; else ok "store.url is required"; fi
if helm template rel "$CHART" --set app.image.tag=1 --set store.url=oci://r/x >/dev/null 2>&1; then fail "app.image.repository is required"; else ok "app.image.repository is required"; fi
if helm template rel "$CHART" "${BASE[@]}" --set store.url=https://r/x >/dev/null 2>&1; then fail "store.url must be oci://"; else ok "store.url must be oci://"; fi
if helm template rel "$CHART" "${BASE[@]}" --set trainer.exitMode=http-probe >/dev/null 2>&1; then fail "http-probe needs a probe url"; else ok "http-probe needs a probe url"; fi
if helm template rel "$CHART" --set app.image.repository=x --set store.url=oci://r/x >/dev/null 2>&1; then fail "a tag or digest is required"; else ok "a tag or digest is required"; fi
out=$(helm template rel "$CHART" "${BASE[@]}" --set trainer.enabled=false)
absent "$out" "kind: CronJob" "trainer.enabled=false renders no CronJob"

echo "== parent chart using the reusable templates"
helm dependency update "$PARENT" >/dev/null 2>&1 || helm dependency build "$PARENT" >/dev/null
out=$(helm template rel "$PARENT" -n default --set warmup.store.pullSecret=pull)
validate "parent chart" "$out"
check "$out" "kind: Deployment" "renders the workload"
check "$out" "name: mnemo-warmup-install" "install init container"
check "$out" "name: mnemo-warmup-fetch" "fetch init container"
check "$out" "- fetch" "fetch runs the fetch command"
check "$out" "command: [\"/mnemo-tools/bin/mnemo-warmup-exec\"]" "app starts through the exec wrapper"
check "$out" "name: MNEMO_WARMUP_CACHE_DIR" "app gets the cache dir"
check "$out" "mountPath: /mnemo-cache" "cache mounted"
check "$out" "path: config.json" "pull secret is mounted for fetch"
check "$out" "kind: CronJob" "the trainer comes with the parent chart"
# the fetch init container and the trainer must describe the launch identically
fetch_args=$(awk '/name: mnemo-warmup-fetch/,/volumeMounts:/' <<<"$out" | grep -E -- '--(jar|store|cache-type|jvm-arg|image-digest)|/app/app.jar|oci://|"auto"|Xmx' | sed 's/^ *//' | sort -u)
train_args=$(awk '/name: trainer/,/volumeMounts:/' <<<"$out" | grep -E -- '--(jar|store|cache-type|jvm-arg|image-digest)|/app/app.jar|oci://|"auto"|Xmx' | sed 's/^ *//' | sort -u)
if [ "$fetch_args" = "$train_args" ] && [ -n "$fetch_args" ]; then ok "fetch and trainer share the same launch arguments"; else fail "fetch and trainer arguments differ"; diff <(echo "$fetch_args") <(echo "$train_args") || true; fi
rm -rf "$PARENT/charts" "$PARENT/Chart.lock"

echo "== example workload chart (examples/helm/example-app)"
EXAMPLE=examples/helm/example-app
helm dependency build "$EXAMPLE" >/dev/null 2>&1 || helm dependency update "$EXAMPLE" >/dev/null
helm lint "$EXAMPLE" >/dev/null && ok "helm lint (example)" || fail "helm lint (example)"
out=$(helm template ex "$EXAMPLE" -n default --set warmup.app.jvmArgs='{-Xmx512m}' --set 'javaOptions={-Xlog:gc}')
validate "example chart" "$out"
check "$out" "kind: Deployment" "example renders the workload"
check "$out" "kind: CronJob" "example renders the trainer"
check "$out" '"-Xmx512m"' "the Deployment's java command uses warmup.app.jvmArgs"
check "$out" '"-Xlog:gc"' "javaOptions reach the java command"
check "$out" '- "--jvm-arg=-Xmx512m"' "the same jvm args reach fetch and the trainer"
rm -rf "$EXAMPLE/charts" "$EXAMPLE/Chart.lock"

if [ "$failures" -eq 0 ]; then echo "ALL OK"; else echo "$failures FAILED"; exit 1; fi
