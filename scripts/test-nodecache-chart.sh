#!/usr/bin/env bash
# Check security and routing properties of the experimental node cache chart.
set -euo pipefail
cd "$(dirname "$0")/.."
chart=deploy/helm/mnemo-nodecache
args=(--kube-version 1.26.0 --set image.tag=test --set configurationSecret=nodecache-config)

rendered=$(helm template cache "$chart" --namespace cache-system "${args[@]}")
for required in 'internalTrafficPolicy: Local' 'kind: DaemonSet' 'runAsNonRoot: true' \
  'readOnlyRootFilesystem: true' 'allowPrivilegeEscalation: false' \
  'resources: ["tokenreviews"]' 'verbs: ["create"]' 'emptyDir:'; do
  grep -Fq "$required" <<<"$rendered" || { printf 'Missing: %s\n' "$required" >&2; exit 1; }
done
if grep -Eq 'hostPath:|hostNetwork: true|privileged: true' <<<"$rendered"; then
  echo 'Unexpected host access or privileged container' >&2
  exit 1
fi
role1=$(helm template cache "$chart" --namespace first "${args[@]}" --show-only templates/rbac.yaml | awk '/name: mnemo-nodecache-/ {print $2; exit}')
role2=$(helm template cache "$chart" --namespace second "${args[@]}" --show-only templates/rbac.yaml | awk '/name: mnemo-nodecache-/ {print $2; exit}')
[[ -n "$role1" && -n "$role2" && "$role1" != "$role2" ]] || { echo 'Cluster role names collide across namespaces' >&2; exit 1; }
if helm template cache "$chart" --namespace cache-system --kube-version 1.26.0 --set configurationSecret=nodecache-config >/dev/null 2>&1; then
  echo 'Missing image tag was accepted' >&2; exit 1
fi
if helm template cache "$chart" --namespace cache-system --kube-version 1.26.0 --set image.tag=test >/dev/null 2>&1; then
  echo 'Missing configuration Secret was accepted' >&2; exit 1
fi
if helm template cache "$chart" --namespace cache-system --kube-version 1.25.0 --set image.tag=test --set configurationSecret=nodecache-config >/dev/null 2>&1; then
  echo 'Unsupported Kubernetes version was accepted' >&2; exit 1
fi
echo 'Node cache chart checks passed'
