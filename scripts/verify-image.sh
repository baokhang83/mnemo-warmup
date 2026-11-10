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
# Every resource name carries a per-run id, and only what this run created is removed, so the script is
# safe on a developer machine (other volumes, a concurrent run) and in CI.
RUN="$$-$(date +%s)"
TAG="mnemo-warmup-tools:verify-$RUN"
NET="mw-verify-$RUN-net"
REG="mw-verify-$RUN-registry"
VOLUMES=()
failures=0

# NOTE: names are registered in this shell, never inside $(...) (a subshell would lose the registration).

cleanup() {
  docker rm -f "$REG" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  for v in ${VOLUMES[@]+"${VOLUMES[@]}"}; do docker volume rm -f "$v" >/dev/null 2>&1 || true; done
  docker image rm -f "$TAG" >/dev/null 2>&1 || true
}
trap cleanup EXIT

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
  tools=mw-verify-$RUN-tools-$n; appvol=mw-verify-$RUN-app-$n; work=mw-verify-$RUN-work-$n; cache=mw-verify-$RUN-cache-$n
  VOLUMES+=("$tools" "$appvol" "$work" "$cache")
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
guard=mw-verify-$RUN-tools-guard
VOLUMES+=("$guard")
docker run --rm -v "$guard":/mnemo-tools "$TAG" cp -a /opt/mnemo-warmup/. /mnemo-tools/
old=eclipse-temurin:17-jre
if docker run --rm -v "$guard":/mnemo-tools:ro "$old" /mnemo-tools/bin/mnemo-warmup fetch --strict >/dev/null 2>&1; then
  ok "fetch skips itself with exit 0 on Java 17"; else fail "fetch on Java 17 must exit 0"; fi
printed=$(docker run --rm -v "$guard":/mnemo-tools:ro "$old" /mnemo-tools/bin/mnemo-warmup run --print -- java -jar x.jar 2>/dev/null) || true
if [ -z "$printed" ]; then ok "run prints nothing on Java 17, so the wrapper keeps the original command"; else fail "run must print nothing on Java 17"; fi
# direct run (no --print): the original command must still start, unchanged, on an old Java
direct=$(docker run --rm -v "$guard":/mnemo-tools:ro "$old" /mnemo-tools/bin/mnemo-warmup run --cache-dir /nowhere -- java -version 2>&1) || true
if grep -q 'openjdk version "17' <<<"$direct"; then ok "run (no --print) starts the original command on Java 17"; else fail "run on Java 17 must start the command"; fi
direct=$(docker run --rm -v "$guard":/mnemo-tools:ro "$old" /mnemo-tools/bin/mnemo-warmup run --spawn -- java -version 2>&1) || true
if grep -q 'openjdk version "17' <<<"$direct"; then ok "run --spawn starts the original command on Java 17"; else fail "run --spawn on Java 17 must start the command"; fi
if docker run --rm "$TAG" /opt/mnemo-warmup/bin/mnemo-warmup fetch >/dev/null 2>&1; then
  ok "fetch exits 0 when the image has no java"; else fail "fetch without java must exit 0"; fi

# A distroless application image has no shell and no cp: everything goes through the native launcher.
echo "== distroless application image (no shell)"
dl=${DISTROLESS_IMAGE:-gcr.io/distroless/java21-debian12}
L=/mnemo-tools/bin/mnemo-launcher
dtools=mw-verify-$RUN-dl-tools; dapp=mw-verify-$RUN-dl-app; dwork=mw-verify-$RUN-dl-work
dcache=mw-verify-$RUN-dl-cache; dempty=mw-verify-$RUN-dl-empty; dbad=mw-verify-$RUN-dl-bad; dzero=mw-verify-$RUN-dl-zero
VOLUMES+=("$dtools" "$dapp" "$dwork" "$dcache" "$dempty" "$dbad" "$dzero")
dstore=oci://$REG:5000/mnemo/verify-distroless
if docker run --rm --entrypoint sh "$dl" -c true >/dev/null 2>&1; then fail "$dl unexpectedly has a shell"; else ok "$dl has no shell"; fi
if docker run --rm -v "$dtools":/mnemo-tools "$TAG" /opt/mnemo-warmup/bin/mnemo-launcher install /mnemo-tools; then
  ok "launcher install copied the tools"; else fail "launcher install"; fi
docker run --rm -v "$dapp":/app -v "$PWD/scripts/verify-image":/src:ro eclipse-temurin:21-jdk sh -c \
  'mkdir /tmp/c && javac -d /tmp/c /src/App.java && jar --create --file /app/app.jar --main-class App -C /tmp/c .'

# dl <extra docker options...> -- <launcher arguments...>: the launcher is the only executable we rely on
dl() {
  local opts=(); while [ "$1" != "--" ]; do opts+=("$1"); shift; done; shift
  docker run --rm ${opts[@]+"${opts[@]}"} -v "$dtools":/mnemo-tools:ro -v "$dapp":/app --entrypoint "$L" "$dl" "$@"
}
dlapp() { # cache volume, then java options: start the app the way the injector does
  local cache=$1; shift
  dl -v "$cache":/cache:ro -e MNEMO_WARMUP_CACHE_DIR=/cache -- exec -- java -Xlog:class+load -Dexit.after.start=1 -Dport=8080 "$@" -jar /app/app.jar 2>&1
}

if dl --network "$NET" -v "$dwork":/work -- jar build --jar /app/app.jar --work-dir /work --exit-mode http-probe \
      --url http://127.0.0.1:8080/health --jvm-arg=-Dport=8080 >/tmp/mw-dl-train.log 2>&1 \
   && dl --network "$NET" -v "$dwork":/work -- jar push --work-dir /work --store "$dstore" --plain-http >>/tmp/mw-dl-train.log 2>&1; then
  ok "trained and pushed through the launcher (jar pass-through)"
else
  fail "distroless train/push (see /tmp/mw-dl-train.log)"
fi
if dl --network "$NET" -v "$dcache":/cache -- fetch --jar /app/app.jar --work-dir /tmp/w --jvm-arg=-Dport=8080 \
      --store "$dstore" --plain-http --out /cache --strict >/tmp/mw-dl-fetch.log 2>&1; then
  ok "fetched through the launcher"; else fail "distroless fetch (see /tmp/mw-dl-fetch.log)"; fi

out=$(dlapp "$dcache")
if grep -q "App source: shared objects file" <<<"$out"; then ok "cache hit: app class loaded from the archive"; else fail "distroless cache hit"; grep -E "run:|mnemo-launcher|App source" <<<"$out" | head -5 || true; fi
if grep -q "^pid=1$" <<<"$out"; then ok "java is PID 1 (the launcher replaced itself)"; else fail "java is not PID 1 on distroless"; fi

docker volume create "$dempty" >/dev/null
out=$(dlapp "$dempty")
if grep -q "^listening$" <<<"$out" && ! grep -q "App source: shared objects file" <<<"$out"; then ok "cache miss: plain start"; else fail "distroless cache miss"; fi

# corrupt archives: one with a different size, one with the right size but zero content
size=$(docker run --rm -v "$dcache":/src:ro "$TAG" sh -c 'wc -c < /src/app.jsa')
docker run --rm -v "$dcache":/src:ro -v "$dbad":/bad "$TAG" sh -c 'cp -a /src/. /bad/ && echo junk > /bad/app.jsa'
docker run --rm -v "$dcache":/src:ro -v "$dzero":/zero "$TAG" sh -c "cp -a /src/. /zero/ && head -c $size /dev/zero > /zero/app.jsa"
for v in "$dbad" "$dzero"; do
  out=$(dlapp "$v")
  if grep -q "^listening$" <<<"$out" && ! grep -q "App source: shared objects file" <<<"$out"; then ok "corrupt cache ($v): plain start"; else fail "distroless corrupt cache ($v)"; grep -E "run:|rror|App source" <<<"$out" | head -5 || true; fi
done

out=$(dl -- exec -- "$L" which-java 2>&1) && rc=0 || rc=$?
if [ "$rc" = 0 ] && grep -q "^/usr/bin/java$" <<<"$out" && grep -q "not a java command" <<<"$out"; then ok "unsupported command runs unchanged"; else fail "distroless unsupported command"; fi
dl -- exec -- java -jar /nonexistent.jar >/dev/null 2>&1 && rc=0 || rc=$?
if [ "$rc" = 1 ]; then ok "the JVM's exit status is the container's"; else fail "exit status was $rc, want 1"; fi

# SIGTERM must reach the JVM (PID 1) and its status must come back: docker stop returns quickly with 143
name=mw-verify-$RUN-dl-term
docker run -d --name "$name" -v "$dtools":/mnemo-tools:ro -v "$dapp":/app -v "$dcache":/cache:ro -e MNEMO_WARMUP_CACHE_DIR=/cache \
  --entrypoint "$L" "$dl" exec -- java -Dport=8080 -jar /app/app.jar >/dev/null
sleep 3
start=$(date +%s); docker stop -t 15 "$name" >/dev/null; elapsed=$(( $(date +%s) - start ))
code=$(docker inspect -f '{{.State.ExitCode}}' "$name"); docker rm -f "$name" >/dev/null
if [ "$code" = 143 ] && [ "$elapsed" -lt 10 ]; then ok "SIGTERM reached the JVM: exit 143 in ${elapsed}s"; else fail "SIGTERM: exit $code after ${elapsed}s"; fi

if [ "$failures" -eq 0 ]; then echo "ALL OK"; else echo "$failures FAILED"; exit 1; fi
