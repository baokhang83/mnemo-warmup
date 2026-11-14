# Heavy workload: ThingsBoard Community Edition

ThingsBoard Community Edition 4.3.1.8 is a real Spring Boot application. The vendor image defaults to monolith mode, and this check uses its in-memory queue and PostgreSQL. It includes persistence, security, the rule engine and transport services. We used the vendor's pinned ARM64 image, initialized an isolated database with `LOAD_DEMO=false`, and trained with `spring-refresh`. The database, application state, startup mode and external integrations affect the resulting archive and startup time.

The vendor supplies a single executable JAR with a prepended shell launcher and normally starts it using `java -cp` and Spring's `PropertiesLauncher`. The injector expects `java -jar`, and Spring Boot's extraction tool rejects the shell prefix. The Dockerfile rebuilds the ZIP without the prefix, keeps each entry and compression method, and uses the Spring Boot extraction tool. It runs the extracted JAR with the vendor configuration directory. This is an adapted launch and image: it is not byte-identical to the vendor's launch path. In particular, external extensions from `LOADER_PATH` are not loaded by this adaptation. Record and validate the chosen configuration when using it in a study.

On Linux ARM64 with Debian OpenJDK 25.0.4.1, the extracted application contained 458 JARs and occupied about 398 MiB. The local trainer produced an AOT archive of **234,356,736 bytes (223.50 MiB)**. A separate run with that archive logged `Opened AOT cache`, logged that ThingsBoard started, and served its login page. This check used 1.5 GiB maximum Java heap and a 3 GiB container limit; it is not a timing benchmark. The login-page response alone does not prove that every transport or feature is ready. The AOT training log includes JVM warnings that some signed-JAR classes were skipped, so do not assume that every class is cached.

The local archive and SHA-256 are in the Git-ignored `../artifacts/20261114-local/thingsboard-ce-25/` directory and `../artifacts/20261114-local/manifest.json`. They are bound to the tested image, runtime and launch settings. Regenerate for the target image and registry. The image requires JDK 25; the application's source is compiled for Java 17, but this setup does not establish JDK 21 compatibility. Use JDK 25 for the first matrix and align its exact vendor and build across tiers before comparing them.

## Reproduce the local functional check

Run from the repository root. The commands start local Docker containers and expose no database or registry port on the host. The credentials below are for this isolated test only. Choose different names if they already exist. This is not a cloud benchmark.

```sh
mvn -q -DskipTests -pl warmup-cli -am package
docker build -t mnemo-thingsboard:4.3.1.8 benchmarks/workload-tiers/thingsboard
mkdir -p /tmp/mnemo-thingsboard-ce-aot
chmod 777 /tmp/mnemo-thingsboard-ce-aot
docker network create mnemo-thingsboard-check
docker run -d --name mnemo-thingsboard-postgres --network mnemo-thingsboard-check \
  -e POSTGRES_DB=thingsboard -e POSTGRES_USER=thingsboard -e POSTGRES_PASSWORD=thingsboard postgres:17-alpine
docker run -d --name mnemo-thingsboard-registry --network mnemo-thingsboard-check registry:2
until docker exec mnemo-thingsboard-postgres pg_isready -U thingsboard; do sleep 1; done

# Initialize a dedicated database once using the vendor's original installer.
docker run --rm --network mnemo-thingsboard-check --memory 3g \
  -e INSTALL_TB=true -e LOAD_DEMO=false -e JAVA_OPTS=-Xmx1500m \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://mnemo-thingsboard-postgres:5432/thingsboard \
  -e SPRING_DATASOURCE_USERNAME=thingsboard -e SPRING_DATASOURCE_PASSWORD=thingsboard \
  -e TB_QUEUE_TYPE=in-memory thingsboard/tb-node:4.3.1.8

# Train the extracted JAR in the adapted image.
docker run --rm --network mnemo-thingsboard-check --memory 3g \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://mnemo-thingsboard-postgres:5432/thingsboard \
  -e SPRING_DATASOURCE_USERNAME=thingsboard -e SPRING_DATASOURCE_PASSWORD=thingsboard \
  -e TB_QUEUE_TYPE=in-memory -e JAVA_TOOL_OPTIONS=-Xmx1500m \
  -v "$PWD/warmup-cli/target/mnemo-warmup.jar:/tools/mnemo.jar:ro" \
  -v /tmp/mnemo-thingsboard-ce-aot:/work --entrypoint java mnemo-thingsboard:4.3.1.8 \
  -jar /tools/mnemo.jar train --jar /opt/thingsboard-app/thingsboard-plain.jar \
  --store oci://mnemo-thingsboard-registry:5000/thingsboard/ce --plain-http --lock none --strict \
  --work-dir /work --timeout-seconds 300 --jvm-arg=-Dplatform=docker \
  --jvm-arg=-Dlogging.config=/usr/share/thingsboard/conf/logback.xml

docker stop mnemo-thingsboard-postgres mnemo-thingsboard-registry
docker rm mnemo-thingsboard-postgres mnemo-thingsboard-registry
docker network rm mnemo-thingsboard-check
```

The registry and database are ephemeral and removed by the final commands. The output file remains under `/tmp` until cleaned. Run a separately initialized database and suitable readiness probe for the cloud experiment; never run this installation against an existing database. Source and vendor instructions: [ThingsBoard repository](https://github.com/thingsboard/thingsboard/tree/v4.3.1.8), [Community Edition Docker installation](https://thingsboard.io/docs/installation/docker/).
