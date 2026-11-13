#!/usr/bin/env bash
# Local ECR token integration check. Requires moto_server 5.1.22 and Maven on PATH.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v moto_server >/dev/null || { echo 'Install moto[server]==5.1.22 in a virtual environment first' >&2; exit 2; }
PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
LOG=$(mktemp)
moto_server -H 127.0.0.1 -p "$PORT" >"$LOG" 2>&1 &
PID=$!
cleanup() { kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; rm -f "$LOG"; }
trap cleanup EXIT
for _ in $(seq 1 50); do
  if curl -fsS "http://127.0.0.1:$PORT/" >/dev/null 2>&1; then break; fi
  kill -0 "$PID" 2>/dev/null || { cat "$LOG" >&2; exit 1; }
  sleep 0.2
done
MNEMO_MOTO_ENDPOINT="http://127.0.0.1:$PORT/" mvn -q -pl warmup-core -Dtest=MotoEcrAuthTest test
printf '%s\n' 'Moto ECR token and local registry challenge passed.'
