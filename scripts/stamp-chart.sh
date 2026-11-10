#!/usr/bin/env bash
# Sets the chart's release version everywhere it must appear, so that the published chart, the app it deploys and the
# default tools image all carry the same number:
#   Chart.yaml: version, appVersion, and the image in the Artifact Hub images annotation
#   values.yaml: the default tools image tag
# Used by the publish-chart workflow (from the release tag) and by scripts/test-chart.sh.
#
# Usage: scripts/stamp-chart.sh <version> [chart-dir]       e.g. scripts/stamp-chart.sh 0.2.1
set -euo pipefail
version=${1:?usage: stamp-chart.sh <version> [chart-dir]}
dir=${2:-"$(dirname "$0")/../deploy/helm/mnemo-warmup"}
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "not a release version (x.y.z): $version" >&2; exit 2; }
chart="$dir/Chart.yaml"; values="$dir/values.yaml"
[ -f "$chart" ] && [ -f "$values" ] || { echo "no chart in $dir" >&2; exit 2; }

sed -E -i.bak \
  -e "s/^version:.*/version: ${version}/" \
  -e "s/^appVersion:.*/appVersion: \"${version}\"/" \
  -e "s#(mnemo-warmup-tools:)[0-9A-Za-z.+-]+#\1${version}#" "$chart"
# the tools image tag is the one `tag:` line that carries the 'matches appVersion' comment
sed -E -i.bak "s/^(    tag: )\"[^\"]*\"( +# matches appVersion.*)$/\1\"${version}\"\2/" "$values"
rm -f "$chart.bak" "$values.bak"

grep -q "^version: ${version}\$" "$chart" && grep -q "^appVersion: \"${version}\"\$" "$chart" \
  && grep -q "mnemo-warmup-tools:${version}\$" "$chart" && grep -q "^    tag: \"${version}\" " "$values" \
  || { echo "stamping did not take effect everywhere in $dir" >&2; exit 1; }
echo "$dir stamped as ${version}"
