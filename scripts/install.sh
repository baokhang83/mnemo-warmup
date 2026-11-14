#!/bin/sh
# Install the platform-independent CLI from a GitHub release. Java 21+ is required to run it.
set -eu

version=${MNEMO_VERSION:-v0.3.0-beta.1}
prefix=${MNEMO_INSTALL_PREFIX:-"${HOME:?HOME is required}/.local"}
printf '%s\n' "$version" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+(-beta\.[0-9]+)?$' || {
    echo "Invalid MNEMO_VERSION: expected a tag such as v0.3.0-beta.1" >&2; exit 1;
}
case "$prefix" in /*) ;; *) echo "MNEMO_INSTALL_PREFIX must be an absolute path" >&2; exit 1 ;; esac
command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 1; }
if command -v sha256sum >/dev/null 2>&1; then
    hash_command=sha256sum
elif command -v shasum >/dev/null 2>&1; then
    hash_command=shasum
else
    echo "sha256sum or shasum is required" >&2; exit 1
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/mnemo-install.XXXXXXXX")
trap 'rm -rf "$tmp"' 0
trap 'exit 1' 1 2 15
base="https://github.com/baokhang83/mnemo-warmup/releases/download/$version"
for file in SHA256SUMS mnemo-warmup.jar mnemo-warmup; do
    curl -fsSL --proto '=https' --proto-redir '=https' --retry 2 -o "$tmp/$file" "$base/$file"
done
for file in mnemo-warmup.jar mnemo-warmup; do
    expected=$(awk -v file="$file" '$2 == file { print $1 }' "$tmp/SHA256SUMS")
    case "$expected" in *[!0-9a-fA-F]*|'') echo "Invalid checksum for $file" >&2; exit 1 ;; esac
    [ "${#expected}" -eq 64 ] || { echo "Invalid checksum for $file" >&2; exit 1; }
    if [ "$hash_command" = sha256sum ]; then
        actual=$(sha256sum "$tmp/$file" | awk '{print $1}')
    else
        actual=$(shasum -a 256 "$tmp/$file" | awk '{print $1}')
    fi
    [ "$actual" = "$expected" ] || { echo "Checksum mismatch for $file" >&2; exit 1; }
done

# Keep the image's wrapper and JAR together. The small PATH entry below locates
# that wrapper relative to its own installation prefix, including paths with spaces.
mkdir -p "$prefix/bin" "$prefix/lib/mnemo-warmup/bin"
cp "$tmp/mnemo-warmup.jar" "$prefix/lib/mnemo-warmup/mnemo-warmup.jar.tmp"
cp "$tmp/mnemo-warmup" "$prefix/lib/mnemo-warmup/bin/mnemo-warmup.tmp"
chmod 755 "$prefix/lib/mnemo-warmup/bin/mnemo-warmup.tmp"
cat > "$prefix/bin/mnemo-warmup.tmp" <<'EOF'
#!/bin/sh
dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
exec "$dir/../lib/mnemo-warmup/bin/mnemo-warmup" "$@"
EOF
chmod 755 "$prefix/bin/mnemo-warmup.tmp"
mv -f "$prefix/lib/mnemo-warmup/mnemo-warmup.jar.tmp" "$prefix/lib/mnemo-warmup/mnemo-warmup.jar"
mv -f "$prefix/lib/mnemo-warmup/bin/mnemo-warmup.tmp" "$prefix/lib/mnemo-warmup/bin/mnemo-warmup"
mv -f "$prefix/bin/mnemo-warmup.tmp" "$prefix/bin/mnemo-warmup"
printf 'Installed mnemo-warmup %s to %s/bin/mnemo-warmup\n' "$version" "$prefix"
case ":$PATH:" in *":$prefix/bin:"*) ;; *) printf 'Add %s/bin to PATH, then run mnemo-warmup --version.\n' "$prefix" ;; esac
