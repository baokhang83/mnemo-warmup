#!/usr/bin/env bash
# Lints and renders the Helm chart with several value sets, checks the output, and renders the parent-chart
# fixture that exercises the reusable templates. With KUBE_CONTEXT=<context> every rendered manifest is also
# validated by that cluster's API server (kubectl apply --dry-run=server); nothing is created.
#
# Usage: scripts/test-chart.sh        Needs helm (and kubectl for KUBE_CONTEXT).
set -euo pipefail
cd "$(dirname "$0")/.."

# Everything below runs on a throw-away COPY of the charts: `helm dependency build` writes charts/ and Chart.lock next to
# a chart, and a developer's own vendored dependencies or lock files must never be touched or removed.
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/deploy" "$WORK/examples"
cp -R deploy/helm "$WORK/deploy/helm"
cp -R examples/helm "$WORK/examples/helm"
CHART="$WORK/deploy/helm/mnemo-warmup"
PARENT="$WORK/deploy/helm/tests/parent"
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

echo "== the chart carries one version"
cv=$(awk '$1 == "version:" {print $2; exit}' "$CHART/Chart.yaml")
av=$(awk '$1 == "appVersion:" {print $2; exit}' "$CHART/Chart.yaml" | tr -d '"')
tt=$(awk '/^tools:/{t=1} t && /^    tag:/ {gsub(/"/,"",$2); print $2; exit}' "$CHART/values.yaml")
ai=$(grep -o 'mnemo-warmup-tools:[^ ]*' "$CHART/Chart.yaml" | head -1 | cut -d: -f2)
if [ "$cv" = "$av" ] && [ "$cv" = "$tt" ] && [ "$cv" = "$ai" ]; then ok "version = appVersion = default tools image tag = Artifact Hub image tag = $cv"; else fail "versions disagree: version=$cv appVersion=$av tools.image.tag=$tt annotation=$ai (run scripts/stamp-chart.sh)"; fi
STAMPED="$WORK/stamped"; cp -R "$CHART" "$STAMPED"
if scripts/stamp-chart.sh 9.8.7 "$STAMPED" >/dev/null && helm lint "$STAMPED" "${BASE[@]}" >/dev/null && helm package "$STAMPED" -d "$WORK" >/dev/null && [ -s "$WORK/mnemo-warmup-9.8.7.tgz" ]; then ok "a stamped chart lints and packages as mnemo-warmup-9.8.7.tgz"; else fail "stamping a release version breaks the chart"; fi
grep -q "ghcr.io/baokhang83/mnemo-warmup-tools:9.8.7" <(helm template s "$STAMPED" "${BASE[@]}") && ok "the stamped default tools image reaches the rendered manifests" || fail "stamped tools image tag not rendered"

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

echo "== tools.delivery=image-volume (docs/adr/0012)"
IV=/mnemo-tools/opt/mnemo-warmup
out=$(helm template rel "$CHART" -n default "${BASE[@]}" --set tools.delivery=image-volume)
validate "image-volume trainer" "$out"
check "$out" "reference: \"ghcr.io/baokhang83/mnemo-warmup-tools:" "the tools are an image volume in the trainer pod"
absent "$out" "mnemo-warmup-install" "no install init container in the trainer pod"
absent "$out" "initContainers:" "no init containers at all"
check "$out" "command: [\"$IV/bin/mnemo-warmup\"]" "the trainer runs the tools from the image volume"
out=$(helm template rel "$PARENT" -n default --set tools.delivery=image-volume --set warmup.tools.delivery=image-volume)
validate "image-volume parent chart" "$out"
absent "$out" "mnemo-warmup-install" "parent: no install init container"
absent "$out" "mnemo-warmup-fetch" "parent: no fetch init container"
# the fixture writes the initContainers key itself; with nothing to include it is an empty key, and no list item may follow it
next_line=$(awk '/initContainers:/{getline n; print n; exit}' <<<"$out")
if [[ "$next_line" != *"- name:"* ]]; then ok "parent: no init containers on the start path"; else fail "parent: an init container follows initContainers: ($next_line)"; fi
check "$out" "reference: \"ghcr.io/baokhang83/mnemo-warmup-tools:" "parent: the tools volume is an image volume"
check "$out" "command: [\"$IV/bin/mnemo-launcher\"]" "parent: the app starts through the launcher"
check "$out" "- exec" "parent: the launcher is told to exec"
check "$out" "- --store" "parent: the fetch options are in the app's args"
check "$out" "                - --
" "parent: the options end with --"
check "$out" "mountPath: /tmp/mnemo-warmup" "parent: the app container has the scratch volume"
app_cache=$(awk '/name: app/,0' <<<"$out" | awk '/mountPath: \/mnemo-cache/{getline n; print n; exit}')
if [[ "$app_cache" != *"readOnly: true"* ]]; then ok "parent: the app container may write the cache (it fetches into it)"; else fail "parent: the cache must not be read-only in the app container"; fi
# the same parent Deployment in the default mode has no trace of it
out=$(helm template rel "$PARENT" -n default)
absent "$out" "- exec" "parent, default mode: no exec prefix"
absent "$out" "reference:" "parent, default mode: no image volume"
check "$out" "name: mnemo-warmup-install" "parent, default mode: still has its init containers"
out=$(helm template rel "$PARENT" -n default --set warmup.tools.delivery=image-volume --set warmup.store.pullSecret=pull)
validate "image-volume parent with pull secret" "$out"
check "$out" "name: mnemo-warmup-fetch" "pull secret: isolated fetch init container"
check "$out" "command: [\"$IV/bin/mnemo-warmup\"]" "pull secret: fetch uses image volume tools"
absent "$out" "name: mnemo-warmup-install" "pull secret: no install init container"
check "$out" "command: [\"$IV/bin/mnemo-launcher\", \"exec\", \"--\"]" "pull secret: application skips inline fetch"
app_part=$(awk '/- name: app$/{inside=1} inside && /^---/{exit} inside' <<<"$out")
[ -n "$app_part" ] || fail "pull secret: application container missing from rendered manifest"
absent "$app_part" "mnemo-registry-pull" "pull secret: application does not mount the credential"
absent "$app_part" "MNEMO_WARMUP_REGISTRY_AUTH" "pull secret: application has no registry auth variable"
app_cache=$(awk '/mountPath: \/mnemo-cache/{getline n; print n; exit}' <<<"$app_part")
if [[ "$app_cache" == *"readOnly: true"* ]]; then ok "pull secret: application mounts the cache read-only"; else fail "pull secret: application cache is not read-only"; fi
if helm template rel "$CHART" "${BASE[@]}" --set tools.delivery=sidecar >/dev/null 2>&1; then fail "an unknown tools.delivery must be refused"; else ok "an unknown tools.delivery is refused"; fi

echo "== example workload chart (examples/helm/example-app)"
EXAMPLE="$WORK/examples/helm/example-app"
helm dependency build "$EXAMPLE" >/dev/null 2>&1 || helm dependency update "$EXAMPLE" >/dev/null
helm lint "$EXAMPLE" >/dev/null && ok "helm lint (example)" || fail "helm lint (example)"
out=$(helm template ex "$EXAMPLE" -n default --set warmup.app.jvmArgs='{-Xmx512m}' --set 'javaOptions={-Xlog:gc}')
validate "example chart" "$out"
check "$out" "kind: Deployment" "example renders the workload"
check "$out" "kind: CronJob" "example renders the trainer"
check "$out" '"-Xmx512m"' "the Deployment's java command uses warmup.app.jvmArgs"
check "$out" '"-Xlog:gc"' "javaOptions reach the java command"
check "$out" '- "--jvm-arg=-Xmx512m"' "the same jvm args reach fetch and the trainer"
absent "$out" "imagePullSecrets" "no imagePullSecrets unless configured"
out=$(helm template ex "$EXAMPLE" -n default --set 'warmup.app.imagePullSecrets[0].name=regcred')
validate "example chart with an image pull secret" "$out"
secrets=$(grep -c "name: regcred" <<<"$out")
if [ "$secrets" -ge 2 ]; then ok "an application image pull secret reaches both the Deployment and the trainer ($secrets places)"; else fail "regcred should appear in the Deployment and the trainer, found $secrets"; fi

if [ "$failures" -eq 0 ]; then echo "ALL OK"; else echo "$failures FAILED"; exit 1; fi
