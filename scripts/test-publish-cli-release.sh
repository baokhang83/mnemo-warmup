#!/usr/bin/env bash
# Tests scripts/publish-cli-release.sh against a fake `gh` that keeps one release in files and logs every call.
# Usage: scripts/test-publish-cli-release.sh
set -uo pipefail
cd "$(dirname "$0")/.."
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
failures=0
ok()   { echo "  ok:   $1"; }
fail() { echo "  FAIL: $1"; failures=$((failures + 1)); }

mkdir -p "$T/bin"
cat >"$T/bin/gh" <<'STUB'
#!/usr/bin/env bash
# fake gh: state in $FAKE/{exists,draft,prerelease,assets/}; every call is appended to $FAKE/calls
echo "$*" >>"$FAKE/calls"
[ "$1" = release ] || { echo "unsupported: $*" >&2; exit 2; }
sub=$2; shift 2
case "$sub" in
  view)
    [ -e "$FAKE/exists" ] || exit 1
    if [[ "$*" == *assets* ]]; then ls "$FAKE/assets" 2>/dev/null; else printf '%s\t%s\n' "$(cat "$FAKE/draft")" "$(cat "$FAKE/prerelease")"; fi ;;
  create)
    touch "$FAKE/exists"; echo false >"$FAKE/draft"; echo false >"$FAKE/prerelease"; mkdir -p "$FAKE/assets"
    [[ "$*" == *--draft* ]] && echo true >"$FAKE/draft"
    [[ "$*" == *--prerelease* ]] && echo true >"$FAKE/prerelease" ;;
  download)
    pattern=; dir=; while [ $# -gt 0 ]; do case "$1" in --pattern) pattern=$2; shift ;; --dir) dir=$2; shift ;; esac; shift; done
    cp "$FAKE/assets/$pattern" "$dir/$pattern" ;;
  upload)
    [ "$(cat "$FAKE/draft")" = true ] || { echo "cannot add an asset to a published release" >&2; exit 1; }
    cp "$2" "$FAKE/assets/$(basename "$2")" ;;
  edit)
    [[ "$*" == *--draft=false* ]] && echo false >"$FAKE/draft" ;;
  *) echo "unsupported: $sub" >&2; exit 2 ;;
esac
exit 0
STUB
chmod +x "$T/bin/gh"

new_case() { # <name>: fresh fake release state and a fresh set of assets
  export FAKE="$T/$1"; rm -rf "$FAKE" "$T/assets"; mkdir -p "$FAKE" "$T/assets"
  : >"$FAKE/calls"
  for f in mnemo-warmup.jar mnemo-warmup SHA256SUMS; do echo "build of $f" >"$T/assets/$f"; done
}
run() { PATH="$T/bin:$PATH" RELEASE_TAG=$1 ASSET_DIR="$T/assets" scripts/publish-cli-release.sh >"$T/out" 2>&1; }
called() { grep -q -- "$1" "$FAKE/calls"; }

echo "== a new release"
new_case fresh
if run v1.2.3; then ok "succeeds"; else fail "failed: $(cat "$T/out")"; fi
called "release create v1.2.3 --draft" && ok "is created as a draft" || fail "not created as a draft: $(cat "$FAKE/calls")"
[ "$(ls "$FAKE/assets" | wc -l | tr -d ' ')" = 3 ] && ok "all three assets are uploaded" || fail "assets: $(ls "$FAKE/assets")"
[ "$(cat "$FAKE/draft")" = false ] && ok "is published at the end" || fail "left as a draft"
first_edit=$(grep -n 'release edit' "$FAKE/calls" | head -1 | cut -d: -f1); last_upload=$(grep -n 'release upload' "$FAKE/calls" | tail -1 | cut -d: -f1)
[ -n "$first_edit" ] && [ "$last_upload" -lt "$first_edit" ] && ok "publishing comes after the last upload" || fail "publish before upload"
! called "prerelease" && ok "a stable tag is not a prerelease" || fail "stable tag marked prerelease"

echo "== a new beta"
new_case beta
run v1.2.3-beta.1 && [ "$(cat "$FAKE/prerelease")" = true ] && ok "a beta is created as a prerelease" || fail "beta: $(cat "$T/out")"

echo "== a retry on a draft that has one asset already"
new_case draft; touch "$FAKE/exists"; echo true >"$FAKE/draft"; echo false >"$FAKE/prerelease"; mkdir -p "$FAKE/assets"; cp "$T/assets/SHA256SUMS" "$FAKE/assets/"
if run v1.2.3; then ok "succeeds"; else fail "failed: $(cat "$T/out")"; fi
! called "release create" && ok "does not create a second release" || fail "created again"
[ "$(grep -c 'release upload' "$FAKE/calls")" = 2 ] && ok "uploads only the two missing assets" || fail "uploads: $(grep 'release upload' "$FAKE/calls")"
[ "$(cat "$FAKE/draft")" = false ] && ok "and publishes it" || fail "still a draft"

echo "== a retry on a release that is already published and complete"
new_case published; touch "$FAKE/exists"; echo false >"$FAKE/draft"; echo false >"$FAKE/prerelease"; mkdir -p "$FAKE/assets"; cp "$T/assets"/* "$FAKE/assets/"
if run v1.2.3; then ok "succeeds"; else fail "failed: $(cat "$T/out")"; fi
! called "release upload" && ! called "release edit" && ok "changes nothing" || fail "touched a published release: $(cat "$FAKE/calls")"

echo "== an asset that differs from this build"
new_case differs; touch "$FAKE/exists"; echo true >"$FAKE/draft"; echo false >"$FAKE/prerelease"; mkdir -p "$FAKE/assets"; echo "other build" >"$FAKE/assets/mnemo-warmup.jar"
if run v1.2.3; then fail "should have failed"; else ok "fails"; fi
grep -q 'differs from this build' "$T/out" && ok "says why" || fail "message: $(cat "$T/out")"
[ "$(cat "$FAKE/draft")" = true ] && ok "and does not publish" || fail "published a release with a differing asset"

echo "== a published release that is missing an asset"
new_case missing; touch "$FAKE/exists"; echo false >"$FAKE/draft"; echo false >"$FAKE/prerelease"; mkdir -p "$FAKE/assets"; cp "$T/assets/mnemo-warmup" "$T/assets/SHA256SUMS" "$FAKE/assets/"
if run v1.2.3; then fail "should have failed"; else ok "fails (assets cannot be added after publication)"; fi

echo "== a beta that exists but is not a prerelease"
new_case notpre; touch "$FAKE/exists"; echo true >"$FAKE/draft"; echo false >"$FAKE/prerelease"; mkdir -p "$FAKE/assets"
if run v1.2.3-beta.2; then fail "should have failed"; else ok "fails"; fi
grep -q 'must be marked as a prerelease' "$T/out" && ok "says why" || fail "message: $(cat "$T/out")"

echo "== bad input"
new_case bad
if run main; then fail "a branch name must be refused"; else ok "a non-release tag is refused"; fi
rm "$T/assets/SHA256SUMS"; if run v1.2.3; then fail "a missing asset file must be refused"; else ok "a missing local asset is refused"; fi

[ "$failures" -eq 0 ] && echo "ALL OK" || { echo "$failures FAILED"; exit 1; }
