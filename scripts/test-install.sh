#!/bin/sh
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/mnemo-install-test.XXXXXXXX")
trap 'rm -rf "$tmp"' 0
trap 'exit 1' 1 2 15
mkdir -p "$tmp/release" "$tmp/mock-bin" "$tmp/home"
printf 'test jar\n' > "$tmp/release/mnemo-warmup.jar"
cp "$root/image/mnemo-warmup" "$tmp/release/mnemo-warmup"
if command -v sha256sum >/dev/null 2>&1; then
    (cd "$tmp/release" && sha256sum mnemo-warmup.jar mnemo-warmup > SHA256SUMS)
else
    (cd "$tmp/release" && shasum -a 256 mnemo-warmup.jar mnemo-warmup > SHA256SUMS)
fi
cat > "$tmp/mock-bin/curl" <<'EOF'
#!/bin/sh
set -eu
output=
while [ "$#" -gt 0 ]; do
    case "$1" in
        -o) output=$2; shift 2 ;;
        --proto|--proto-redir|--retry) shift 2 ;;
        -*) shift ;;
        *) url=$1; shift ;;
    esac
done
cp "$FIXTURE_DIR/${url##*/}" "$output"
EOF
cat > "$tmp/mock-bin/java" <<'EOF'
#!/bin/sh
if [ "$1" = -version ]; then echo 'openjdk version "21.0.1"' >&2; exit 0; fi
printf '%s\n' "$@" > "$JAVA_LOG"
EOF
chmod +x "$tmp/mock-bin/curl" "$tmp/mock-bin/java"
export FIXTURE_DIR="$tmp/release" JAVA_LOG="$tmp/java-args" HOME="$tmp/home"
export PATH="$tmp/mock-bin:$PATH" MNEMO_VERSION=v0.3.0-beta.2
export MNEMO_INSTALL_PREFIX="$tmp/install path"
sh "$root/scripts/install.sh"
"$MNEMO_INSTALL_PREFIX/bin/mnemo-warmup" --version
grep -Fx -- '-jar' "$JAVA_LOG" >/dev/null
grep -Fx -- "$MNEMO_INSTALL_PREFIX/bin/../lib/mnemo-warmup/mnemo-warmup.jar" "$JAVA_LOG" >/dev/null || \
    grep -F '/lib/mnemo-warmup/bin/../mnemo-warmup.jar' "$JAVA_LOG" >/dev/null
grep -Fx -- '--version' "$JAVA_LOG" >/dev/null
sh "$root/scripts/install.sh" >/dev/null
default_output=$(unset MNEMO_VERSION; sh "$root/scripts/install.sh")
printf '%s\n' "$default_output" | grep -F 'Installed mnemo-warmup v0.3.0-beta.2 ' >/dev/null
cp "$MNEMO_INSTALL_PREFIX/lib/mnemo-warmup/mnemo-warmup.jar" "$tmp/original-jar"
printf 'corrupt\n' >> "$tmp/release/mnemo-warmup.jar"
if sh "$root/scripts/install.sh" >/dev/null 2>&1; then
    echo 'Installer accepted corrupt data' >&2; exit 1
fi
cmp "$tmp/original-jar" "$MNEMO_INSTALL_PREFIX/lib/mnemo-warmup/mnemo-warmup.jar"
if MNEMO_INSTALL_PREFIX="$tmp/fresh" sh "$root/scripts/install.sh" >/dev/null 2>&1; then
    echo 'Installer accepted corrupt data on a fresh install' >&2; exit 1
fi
[ ! -e "$tmp/fresh/bin/mnemo-warmup" ]
"$MNEMO_INSTALL_PREFIX/bin/mnemo-warmup" --version
cp "$tmp/original-jar" "$tmp/release/mnemo-warmup.jar"
mv "$tmp/release/SHA256SUMS" "$tmp/release/SHA256SUMS.saved"
if sh "$root/scripts/install.sh" >/dev/null 2>&1; then
    echo 'Installer accepted a missing checksum manifest' >&2; exit 1
fi
cmp "$tmp/original-jar" "$MNEMO_INSTALL_PREFIX/lib/mnemo-warmup/mnemo-warmup.jar"
mv "$tmp/release/SHA256SUMS.saved" "$tmp/release/SHA256SUMS"
if MNEMO_VERSION='v1.0.0/../../bad' sh "$root/scripts/install.sh" >/dev/null 2>&1; then
    echo 'Installer accepted an unsafe version' >&2; exit 1
fi
if MNEMO_INSTALL_PREFIX=relative sh "$root/scripts/install.sh" >/dev/null 2>&1; then
    echo 'Installer accepted a relative prefix' >&2; exit 1
fi
echo 'Installer checks passed'
