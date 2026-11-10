# ADR 0006: Publishing the Helm chart

Status: accepted (2026-11-10) · Builds on ADR 0005

## Context

The chart (ADR 0005) was usable only by cloning this repository, and its version (0.1.0) had drifted from the release it
deploys (0.2.0): nothing set it on release. Teams need a normal `helm repo add`, and Artifact Hub is where Helm users look.
mnemo-jvm-warden already publishes charts for Artifact Hub, and learned that Artifact Hub's tracker times out on GitHub
release assets.

## Decision

**A classic Helm repository, as in mnemo-jvm-warden.** `chart-releaser` packages the chart into a GitHub release
(`mnemo-warmup-<version>`, not marked "Latest", so the app release keeps that label) and updates `index.yaml` on `gh-pages`;
a second workflow mirrors the package onto GitHub Pages and rewrites the index URLs, because Artifact Hub's tracker times out on
release assets. `https://baokhang83.github.io/mnemo-warmup/` is the repository URL. The coverage report shares `gh-pages`, so its
deploy step now excludes `index.yaml`, `charts/**` and `artifacthub-repo.yml` from its clean-up.

**The chart version is the release version.** `scripts/stamp-chart.sh` sets `version`, `appVersion`, the Artifact Hub image
annotation and the default tools image tag to the release number; the workflow runs it from the tag (or from a manual input
for a version without a new tag, such as the first one, 0.2.0) and refuses to publish if that tools image does not exist.
`scripts/test-chart.sh` fails if the four numbers in the repository disagree and checks that a stamped chart lints and packages.
A version can be published once.

**Artifact Hub** is registered by the repository owner (a Helm repository at the Pages URL); the `repositoryID` it issues goes
into `artifacthub-repo.yml`, which the coverage deploy copies to the Pages root to claim ownership. The chart carries Artifact
Hub annotations (license, category, images, links), and its README, which is bundled into the package, uses absolute links.

## Rejected

- **OCI on GHCR:** simpler (one workflow, no Pages mirror), but the owner chose the same pattern as mnemo-jvm-warden, which is
  already proven with their Artifact Hub account.
- **Independent chart version:** it had already drifted once.

## Consequences

- GitHub Pages must be enabled for `gh-pages` (it was not when this was written).
- Repository files are not rewritten on release: the stamped numbers exist in the published package, not in `main`, so the files
  in `main` carry the last number someone set by hand (they are consistent with each other, which the test checks).
- Dependencies on this chart should use a version range or be bumped on purpose; the fixtures and the example chart use ranges.
