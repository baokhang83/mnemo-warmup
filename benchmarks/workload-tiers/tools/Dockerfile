FROM maven:3.9-eclipse-temurin-25 AS cli
WORKDIR /src
COPY pom.xml ./
COPY coverage/pom.xml coverage/
COPY warmup-core/pom.xml warmup-core/
COPY warmup-core/src warmup-core/src
COPY warmup-cli/pom.xml warmup-cli/
COPY warmup-cli/src warmup-cli/src
RUN mvn -B -ntp -q -pl warmup-cli -am -DskipTests -Djacoco.skip=true \
    -Dmaven.javadoc.skip=true -Dmaven.source.skip=true package

FROM golang:1.24 AS launcher
WORKDIR /src/launcher
COPY launcher/ ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o /out/mnemo-launcher . \
 && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o /out/mnemo-nodecache ./cmd/mnemo-nodecache
COPY benchmarks/workload-tiers/tools/loadgen/ /src/loadgen/
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -C /src/loadgen -trimpath -ldflags="-s -w" -o /out/mnemo-benchload .

FROM busybox:1.37
COPY --from=cli /src/warmup-cli/target/mnemo-warmup.jar /opt/mnemo-warmup/mnemo-warmup.jar
COPY --from=launcher /out/mnemo-launcher /opt/mnemo-warmup/bin/mnemo-launcher
COPY --from=launcher /out/mnemo-nodecache /opt/mnemo-warmup/bin/mnemo-nodecache
COPY --from=launcher /out/mnemo-benchload /opt/mnemo-warmup/bin/mnemo-benchload
COPY image/mnemo-warmup /opt/mnemo-warmup/bin/mnemo-warmup
COPY scripts/mnemo-warmup-exec /opt/mnemo-warmup/bin/mnemo-warmup-exec
RUN chmod 0755 /opt/mnemo-warmup/bin/mnemo-launcher \
    /opt/mnemo-warmup/bin/mnemo-nodecache \
    /opt/mnemo-warmup/bin/mnemo-benchload \
    /opt/mnemo-warmup/bin/mnemo-warmup \
    /opt/mnemo-warmup/bin/mnemo-warmup-exec
