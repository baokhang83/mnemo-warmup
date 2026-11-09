#!/usr/bin/env bash
# Verifies dynamic AppCDS behaviour on several JDKs (default 21 22 23 24) using Docker.
# Usage: scripts/verify-appcds.sh [jdk-feature-version ...]
# Output: tab-separated rows on stdout; training notes on stderr.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$(mktemp -d)
JDKS=("$@"); [ ${#JDKS[@]} -gt 0 ] || JDKS=(21 22 23 24)
PREV=""
for n in "${JDKS[@]}"; do
  mkdir -p "$OUT/$n"
  docker run --rm \
    -v "$HERE/verify-appcds-in-container.sh:/verify.sh:ro" \
    -v "$OUT:/work/out" -e FOREIGN_ARCHIVE="${PREV:+/work/out/$PREV.jsa}" \
    "eclipse-temurin:$n-jdk" bash /verify.sh
  PREV="jdk-$n"
done
