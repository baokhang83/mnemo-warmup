#!/bin/sh
# Writes llms-full.txt (the documentation in one file, for LLM tools) to the path given as $1.
# The files and their order are listed here; llms.txt is the hand-written index.
set -eu
out=${1:?usage: build-llms-full.sh <output-file>}
cd "$(dirname "$0")/.."
{
  echo "# mnemo-warmup: full documentation"
  echo
  echo "> Generated from the repository at build time. Index: llms.txt. Source: https://github.com/baokhang83/mnemo-warmup"
  for f in README.md docs/configuration.md docs/operations.md docs/offline-injector.md docs/reference.md \
           docs/helm-chart.md docs/registry-auth.md docs/aws-workload-identity.md docs/archive-retention.md \
           docs/appcds-jdk-matrix.md benchmarks/README.md docs/PLAN.md; do
    [ -f "$f" ] || { echo "missing $f" >&2; exit 1; }
    printf '\n\n---\n\n<!-- %s -->\n\n' "$f"
    cat "$f"
  done
} > "$out"
