#!/usr/bin/env bash
# End-to-end check of the tools image, in Docker, on the JDKs given (default: 21 25):
#
#   1. build the tools image and copy it into a volume the way an init container would,
#   2. in an "application image" (eclipse-temurin:<n>-jre): train an archive and push it to a local registry,
#   3. in a fresh container of the same image: fetch it,
#   4. start the app through mnemo-warmup-exec and assert that the app class comes from the archive,
#      that java is PID 1, and that a changed heap flag falls back to a plain start.
#
# Usage: scripts/verify-image.sh [jdk ...]      Needs Docker (and network access to pull images).
set -euo pipefail

cd "$(dirname "$0")/.."
JDKS=("$@"); [ ${#JDKS[@]} -gt 0 ] || JDKS=(21 25)
TAG=mnemo-warmup-tools:verify
NET=mw-verify-net
REG=mw-verify-registry
failures=0

cleanup() {
  docker rm -f "$REG" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  for v in $(docker volume ls -q --filter name=mw-verify-); do docker volume rm -f "$v" >/dev/null 2>&1 || true; done
}
trap cleanup EXIT
cleanup

ok()   { echo "  ok:   $1"; }
fail() { echo "  FAIL: $1"; failures=$((failures + 1)); }

echo "== building the tools image"
docker build -q -f image/Dockerfile -t "$TAG" --build-arg VERSION=verify . >/dev/null
docker network create "$NET" >/dev/null
docker run -d --name "$REG" --network "$NET" registry:2 >/dev/null
sleep 2

for n in "${JDKS[@]}"; do
  echo "== JDK $n"
  app=eclipse-temurin:$n-jre
  jdk=eclipse-temurin:$n-jdk
  tools=mw-verify-tools-$n; appvol=mw-verify-app-$n; work=mw-verify-work-$n; cache=mw-verify-cache-$n
  store=oci://$REG:5000/mnemo/verify-$n

  # init-container pattern: copy the tools into a shared volume
  docker run --rm -v "$tools":/mnemo-tools "$TAG" cp -a /opt/mnemo-warmup/. /mnemo-tools/

  # the "application": a jar at a fixed path in a volume, so training and running see the same file and mtime
  docker run --rm -v "$appvol":/app -v "$PWD/scripts/verify-image":/src:ro "$jdk" sh -c \
    'mkdir /tmp/c && javac -d /tmp/c /src/App.java && jar --create --file /app/app.jar --main-class App -C /tmp/c .'

  # the launcher works in the application image
  docker run --rm -v "$tools":/mnemo-tools:ro "$app" /mnemo-tools/bin/mnemo-warmup --version >/dev/null \
    && ok "launcher runs in $app" || fail "launcher does not run in $app"

  # train + push (the trainer, in the application image)
  docker run --rm --network "$NET" -v "$tools":/mnemo-tools:ro -v "$appvol":/app -v "$work":/work "$app" sh -c "
    /mnemo-tools/bin/mnemo-warmup build --jar /app/app.jar --work-dir /work --exit-mode http-probe \
      --url http://127.0.0.1:8080/health --jvm-arg=-Dport=8080 &&
    /mnemo-tools/bin/mnemo-warmup push --work-dir /work --store $store --plain-http" >/tmp/mw-train-$n.log 2>&1 \
    && ok "trained and pushed" || { fail "train/push (see /tmp/mw-train-$n.log)"; continue; }

  # fetch (the init container, in a fresh container of the application image)
  docker run --rm --network "$NET" -v "$tools":/mnemo-tools:ro -v "$appvol":/app -v "$cache":/cache "$app" \
    /mnemo-tools/bin/mnemo-warmup fetch --jar /app/app.jar --work-dir /tmp/w --jvm-arg=-Dport=8080 \
      --store "$store" --plain-http --out /cache --strict >/tmp/mw-fetch-$n.log 2>&1 \
    && ok "fetched" || { fail "fetch (see /tmp/mw-fetch-$n.log)"; continue; }

  runapp() { # extra java options as arguments
    docker run --rm -v "$tools":/mnemo-tools:ro -v "$appvol":/app -v "$cache":/cache:ro \
      -e MNEMO_WARMUP_CACHE_DIR=/cache --entrypoint /mnemo-tools/bin/mnemo-warmup-exec "$app" \
      java -Xlog:class+load -Dexit.after.start=1 -Dport=8080 "$@" -jar /app/app.jar 2>&1
  }

  out=$(runapp)
  if grep -q "App source: shared objects file" <<<"$out"; then ok "app class loaded from the archive"; else fail "archive not used"; grep -E "run:|App source" <<<"$out" | head -5 || true; fi
  if grep -q "^pid=1$" <<<"$out"; then ok "java is PID 1"; else fail "java is not PID 1"; grep "pid=" <<<"$out" | head -2 || true; fi

  # trained without -Xmx; running with it must fall back, not break
  out=$(runapp -Xmx256m)
  if grep -q "run: archive does not match" <<<"$out" && ! grep -q "App source: shared objects file" <<<"$out" && grep -q "^listening$" <<<"$out"; then
    ok "changed heap flag falls back to a plain start"
  else
    fail "fallback on changed -Xmx"; grep -E "run:|App source|listening" <<<"$out" | head -5 || true
  fi
done

echo "== launcher guards"
docker run --rm -v mw-verify-tools-guard:/mnemo-tools "$TAG" cp -a /opt/mnemo-warmup/. /mnemo-tools/
old=eclipse-temurin:17-jre
if docker run --rm -v mw-verify-tools-guard:/mnemo-tools:ro "$old" /mnemo-tools/bin/mnemo-warmup fetch --strict >/dev/null 2>&1; then
  ok "fetch skips itself with exit 0 on Java 17"; else fail "fetch on Java 17 must exit 0"; fi
printed=$(docker run --rm -v mw-verify-tools-guard:/mnemo-tools:ro "$old" /mnemo-tools/bin/mnemo-warmup run --print -- java -jar x.jar 2>/dev/null) || true
if [ -z "$printed" ]; then ok "run prints nothing on Java 17, so the wrapper keeps the original command"; else fail "run must print nothing on Java 17"; fi
if docker run --rm "$TAG" /opt/mnemo-warmup/bin/mnemo-warmup fetch >/dev/null 2>&1; then
  ok "fetch exits 0 when the image has no java"; else fail "fetch without java must exit 0"; fi

if [ "$failures" -eq 0 ]; then echo "ALL OK"; else echo "$failures FAILED"; exit 1; fi
