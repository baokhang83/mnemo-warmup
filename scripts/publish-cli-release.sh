#!/usr/bin/env bash
# Publishes the CLI assets for a release tag as a GitHub release: create it as a DRAFT, upload every asset, then publish.
# A published release can be immutable (docs/security-roadmap.md, #142), and an immutable release accepts no new assets, so the
# assets must be complete before it is published. Safe to re-run: an existing release is reused, assets that are already there
# must be byte-identical to this build (never silently replaced), and missing ones are uploaded.
#
# Usage: RELEASE_TAG=v1.2.3 [ASSET_DIR=release-cli] scripts/publish-cli-release.sh      Needs gh (GH_TOKEN) and the tag to exist.
set -euo pipefail
tag=${RELEASE_TAG:?RELEASE_TAG is required}
dir=${ASSET_DIR:-release-cli}
files=(mnemo-warmup.jar mnemo-warmup SHA256SUMS)
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-beta\.[0-9]+)?$ ]] || { echo "::error::Not a release tag: $tag"; exit 1; }
for f in "${files[@]}"; do [ -s "$dir/$f" ] || { echo "::error::Missing asset $dir/$f"; exit 1; }; done

if state=$(gh release view "$tag" --json isDraft,isPrerelease --jq '[.isDraft,.isPrerelease]|@tsv' 2>/dev/null) && [ -n "$state" ]; then
  draft=${state%%$'\t'*}; prerelease=${state##*$'\t'}
else
  create=(--draft --verify-tag --title "$tag" --notes "CLI release $tag")
  prerelease=false
  if [[ "$tag" == *-beta.* ]]; then create+=(--prerelease); prerelease=true; fi
  gh release create "$tag" "${create[@]}"
  draft=true
fi

if [[ "$tag" == *-beta.* && "$prerelease" != true ]]; then
  echo "::error::The existing beta release must be marked as a prerelease"; exit 1
fi

# A retry must not silently replace assets that are already on the release.
existing=$(mktemp -d); trap 'rm -rf "$existing"' EXIT
have=$(gh release view "$tag" --json assets --jq '.assets[].name')
for f in "${files[@]}"; do
  if grep -Fxq "$f" <<<"$have"; then
    gh release download "$tag" --pattern "$f" --dir "$existing"
    cmp "$dir/$f" "$existing/$f" || { echo "::error::Existing release asset $f differs from this build"; exit 1; }
  else
    gh release upload "$tag" "$dir/$f"
  fi
done

if [ "$draft" = true ]; then gh release edit "$tag" --draft=false; fi
echo "Release $tag is published with: ${files[*]}"
