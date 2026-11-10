# syntax=docker/dockerfile:1
#
# The mnemo-warmup TOOLS image: the CLI, a launcher and the exec wrapper, with no JDK.
#
# The tool must run INSIDE the application image, so that the JDK build, the classpath JARs, their
# paths and modification times are the ones the application really runs with (they are all part of
# what an archive is valid for). This image is therefore only a carrier:
#
#   * at deploy time, an init container runs it to copy /opt/mnemo-warmup into a shared volume, or
#   * at image build time, the application's Dockerfile does
#       COPY --from=ghcr.io/baokhang83/mnemo-warmup-tools:<version> /opt/mnemo-warmup /opt/mnemo-warmup
#
# Build from the repository root:  docker build -f image/Dockerfile -t mnemo-warmup-tools .

FROM maven:3.9-eclipse-temurin-21 AS build
# The release version (the image tag). Stamped into the jar so `mnemo-warmup --version` and the injector's default tools image
# (ghcr.io/baokhang83/mnemo-warmup-tools:<this CLI's version>) agree with the tag the image is published under.
ARG VERSION=dev
WORKDIR /src
COPY pom.xml ./
COPY warmup-core/pom.xml warmup-core/
COPY warmup-cli/pom.xml warmup-cli/
COPY coverage/pom.xml coverage/
COPY warmup-core/src warmup-core/src
COPY warmup-cli/src warmup-cli/src
RUN if [ "$VERSION" != dev ]; then mvn -B -ntp -q versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false; fi \
 && mvn -B -ntp -q -pl warmup-cli -am -DskipTests -Djacoco.skip=true \
        -Dmaven.javadoc.skip=true -Dmaven.source.skip=true package

# The native launcher: a static Go binary for distroless images. Built on the build platform and
# cross-compiled for the target, so the multi-arch image does not emulate a Go build under QEMU.
FROM --platform=$BUILDPLATFORM golang:1.24 AS launcher
ARG TARGETOS=linux
ARG TARGETARCH
WORKDIR /src/launcher
COPY launcher/ ./
# Tests run for the BUILD platform (a test binary for another architecture cannot execute here);
# only the shipped binary is cross-compiled.
RUN CGO_ENABLED=0 go test ./... \
 && CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath -ldflags="-s -w" -o /out/mnemo-launcher .

# busybox gives the init-container pattern `cp` and the wrapper its `sh`; it is about 4 MB.
FROM busybox:1.37
ARG VERSION=dev
LABEL org.opencontainers.image.title="mnemo-warmup-tools" \
      org.opencontainers.image.description="mnemo-warmup CLI, launcher and exec wrapper (no JDK); run inside the application image" \
      org.opencontainers.image.source="https://github.com/baokhang83/mnemo-warmup" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}"
COPY --from=build /src/warmup-cli/target/mnemo-warmup.jar /opt/mnemo-warmup/mnemo-warmup.jar
COPY --from=launcher --chmod=0755 /out/mnemo-launcher /opt/mnemo-warmup/bin/mnemo-launcher
COPY --chmod=0755 image/mnemo-warmup /opt/mnemo-warmup/bin/mnemo-warmup
COPY --chmod=0755 scripts/mnemo-warmup-exec /opt/mnemo-warmup/bin/mnemo-warmup-exec
