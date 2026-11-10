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
WORKDIR /src
COPY pom.xml ./
COPY warmup-core/pom.xml warmup-core/
COPY warmup-cli/pom.xml warmup-cli/
COPY coverage/pom.xml coverage/
COPY warmup-core/src warmup-core/src
COPY warmup-cli/src warmup-cli/src
RUN mvn -B -ntp -q -pl warmup-cli -am -DskipTests -Djacoco.skip=true \
        -Dmaven.javadoc.skip=true -Dmaven.source.skip=true package

# busybox gives the init-container pattern `cp` and the wrapper its `sh`; it is about 4 MB.
FROM busybox:1.37
ARG VERSION=dev
LABEL org.opencontainers.image.title="mnemo-warmup-tools" \
      org.opencontainers.image.description="mnemo-warmup CLI, launcher and exec wrapper (no JDK); run inside the application image" \
      org.opencontainers.image.source="https://github.com/baokhang83/mnemo-warmup" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}"
COPY --from=build /src/warmup-cli/target/mnemo-warmup.jar /opt/mnemo-warmup/mnemo-warmup.jar
COPY --chmod=0755 image/mnemo-warmup /opt/mnemo-warmup/bin/mnemo-warmup
COPY --chmod=0755 scripts/mnemo-warmup-exec /opt/mnemo-warmup/bin/mnemo-warmup-exec
