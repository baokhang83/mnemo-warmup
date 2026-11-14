# Spring Boot workload tiers

The minimal, standard and larger sample workloads are synthetic Spring Boot 3.5.16 applications. ThingsBoard Community Edition is a real Spring Boot application and is the heavy-tier candidate. Archive sizes are observed outcomes, not targets guaranteed by dependencies.

| Tier | Application | What runs at startup | Important limits |
| --- | --- | --- | --- |
| Minimal | Spring Web and Actuator | HTTP server and readiness | No database or security |
| Standard | Minimal plus JPA/Hibernate, PostgreSQL driver, Security, Validation and caching | Connection pool, one entity and repository, security filter chain | Security permits all requests; it is not representative authentication |
| Larger sample | Standard plus Springdoc OpenAPI, Kafka, RabbitMQ and Prometheus | Those dependencies and messaging templates are available; no broker is needed to start | This remains a synthetic service, not a monolith. |
| Heavy | ThingsBoard Community Edition 4.3.1.8, configured as a monolith with PostgreSQL | Real server with persistence, security, rule engine and transports | Adapted image; in-memory queue, empty demo data and `spring-refresh` training. See [ThingsBoard setup](thingsboard/README.md). |

Standard and larger use a PostgreSQL database. Run `postgres-init.sql` **once** against a dedicated database before training and measurement. The app does not create or drop its schema on startup, so a pod burst does not race over schema changes. Both expose `/customers`; the larger tier also exposes `/v3/api-docs`, `/actuator/prometheus`, `/events/kafka` and `/events/rabbit`. Posting events requires separately configured brokers and topics/queues. Do not call those endpoints without them.

## Feasibility check: actual archive sizes

On 2026-11-14, the workloads were built as Linux ARM64 container images and trained locally using the same mnemo-warmup `spring-refresh` mode and a local PostgreSQL 17 instance. The application files are extracted Spring Boot images. The sample workloads used Eclipse Temurin 21.0.13 for AppCDS and 25.0.5 for AOT; ThingsBoard used Debian OpenJDK 25.0.4.1 for AOT. Values are bytes on disk and in the OCI blob; MiB = 1,048,576 bytes. This was a functional check, not a performance benchmark.

| Tier | Extracted app files | JARs | JDK 21 AppCDS | JDK 25 AOT |
| --- | ---: | ---: | ---: | ---: |
| Minimal | 22.55 MiB | 37 | 36,044,800 bytes (34.38 MiB) | 55,115,776 bytes (52.56 MiB) |
| Standard | 57.91 MiB | 70 | 84,606,976 bytes (80.69 MiB) | 107,151,360 bytes (102.19 MiB) |
| Larger sample | 86.22 MiB | 98 | 96,337,920 bytes (91.88 MiB) | 119,799,808 bytes (114.25 MiB) |
| ThingsBoard CE | about 398 MiB | 458 | Not measured | 234,356,736 bytes (223.50 MiB) |

Each sample archive trained successfully and its matching application reached the readiness endpoint when started with it. ThingsBoard CE opened its archive, logged that it started, and served its login page; this is not a full application readiness or functional test. JVM logs confirmed that the AppCDS and AOT files were opened; the standard and larger applications also returned a response from `/customers`, and the larger application served OpenAPI and Prometheus. The sample database had no customer rows; ThingsBoard had its required system data but no demo data. Broker traffic was not exercised. No startup savings or cloud performance were measured. Absolute values depend on the JDK build, architecture, dependencies and training path. The local files and their checksums are retained under `artifacts/20261114-local/`; that directory is ignored by Git.

ThingsBoard CE reaches the proposed heavy archive range without padding. These first results used different JDK builds and are retained as historical feasibility results only. The 130–160 MiB middle archive range has not yet been reached. Archive size reflects the classes and metadata loaded during training, so the size ranges must remain observations rather than pass/fail targets.

## JDK-aligned local series

On 2026-11-14, the three synthetic workloads were rebuilt as Linux ARM64 images on the same pinned Debian OpenJDK 25.0.4.1 runtime image used for ThingsBoard CE. All four runtimes report `25.0.4.1+1-1~deb13u1`; the synthetic image IDs, exact archive hashes, and training configuration are in the ignored `artifacts/20261114-debian25/manifest.json`. The ThingsBoard archive from the same JDK build is retained under `artifacts/20261114-local/` and included in that manifest. All archives used `spring-refresh` and Leyden AOT.

| Tier | Runtime | AOT archive | Bytes | Functional check |
| --- | --- | --- | ---: | --- |
| Minimal | Debian OpenJDK 25.0.4.1 | `app.aot` | 57,344,000 (54.69 MiB) | Readiness endpoint returned `UP` |
| Standard | Debian OpenJDK 25.0.4.1 | `app.aot` | 109,445,120 (104.38 MiB) | Readiness endpoint returned `UP` |
| Larger sample | Debian OpenJDK 25.0.4.1 | `app.aot` | 122,290,176 (116.63 MiB) | Readiness endpoint returned `UP` |
| ThingsBoard CE 4.3.1.8 | Debian OpenJDK 25.0.4.1 | `app.aot` | 234,356,736 (223.50 MiB) | JVM opened archive, startup logged, login page returned HTTP 200; incomplete readiness check |

For this check, PostgreSQL 17 was initialized once with `postgres-init.sql`; the three sample apps used the same database URL and no extra JVM arguments. Their container image IDs and archive SHA-256 values are recorded in the manifest. The sample apps started with their generated archive mounted read-only and returned `{"status":"UP"}` from `/actuator/health/readiness`. The ThingsBoard entry uses the adapted `java -jar` launch described above, with a 1.5 GiB heap, 3 GiB container limit, in-memory queue, and no demo data. These checks confirm startup functionality only: no startup timings were collected and no cloud performance measurements were made. The generated archives and functional logs are Git-ignored.

## Reproduce the local training check

Run from the repository root to reproduce the three synthetic archives. This starts a temporary, local PostgreSQL and OCI registry on a dedicated Docker network; it does not create cloud resources. It builds the CLI and three application images on the pinned Debian OpenJDK 25.0.4.1 base. The archive files and training logs are retained under `benchmarks/workload-tiers/artifacts/20261114-debian25/`. Use unique container/network names if these names already exist.

```sh
mvn -q -DskipTests -pl warmup-cli -am package
mkdir -p benchmarks/workload-tiers/artifacts/20261114-debian25
chmod 777 benchmarks/workload-tiers/artifacts/20261114-debian25
docker network create mnemo-tiers-debian25
docker run -d --name mnemo-tiers-db-debian25 --network mnemo-tiers-debian25 \
  -e POSTGRES_DB=bench -e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench postgres:17-alpine
docker run -d --name mnemo-tiers-registry-debian25 --network mnemo-tiers-debian25 registry:2
# Wait until this succeeds before initializing the schema.
until docker exec mnemo-tiers-db-debian25 pg_isready -U bench -d bench; do sleep 1; done
docker exec -i mnemo-tiers-db-debian25 psql -U bench -d bench < benchmarks/workload-tiers/postgres-init.sql

for tier in minimal standard heavy; do
  docker build -t "mnemo-tier-$tier:debian25" "benchmarks/workload-tiers/$tier"
  mkdir -p "benchmarks/workload-tiers/artifacts/20261114-debian25/$tier"
  docker run --rm --network mnemo-tiers-debian25 \
      -e BENCH_DB_URL=jdbc:postgresql://mnemo-tiers-db-debian25:5432/bench \
      -v "$PWD/warmup-cli/target/mnemo-warmup.jar:/tools/mnemo.jar:ro" \
      -v "$PWD/benchmarks/workload-tiers/artifacts/20261114-debian25/$tier:/work" \
      --entrypoint java "mnemo-tier-$tier:debian25" \
      -jar /tools/mnemo.jar train --jar /app/app.jar \
      --store "oci://mnemo-tiers-registry-debian25:5000/tiers/$tier" \
      --plain-http --lock none --strict --work-dir /work --timeout-seconds 180
done

docker stop mnemo-tiers-db-debian25 mnemo-tiers-registry-debian25
docker rm mnemo-tiers-db-debian25 mnemo-tiers-registry-debian25
docker network rm mnemo-tiers-debian25
```

The registry above is ephemeral. Its blobs disappear when its container is removed; the local archive files remain under the ignored artifacts directory. Train again against the actual workload image and target registry for the cloud experiment. JVM archives are specific to their JDK, application files, paths and launch configuration; these local files are not interchangeable with cloud builds.

## Combined cloud study

For each tier, measure matched uncached and archive-enabled deployments of the **same** image and configuration. Report uncached Ready minus archive-enabled Ready within that tier, rather than comparing startup times across different tiers. Keep the JDK vendor, exact build and mechanism fixed across tiers, or run JDK 21 AppCDS and JDK 25 AOT as separate series; do not assume a fixed size difference between them. Align the existing images before the run.

For one new pod and a burst of nine new pods, compare no tool, per-pod archive downloads, a cold node cache and a warm node cache. Keep node, registry, database state, resources, readiness probe and training path fixed across arms. Interleave repeated runs. Capture scheduling, init/fetch, container start and application-to-Ready timestamps, plus archive bytes, cache hits/misses, transfer/hash duration, network counters and failures. Plot fetch/init and application-to-Ready separately, with a matching uncached bar. Account separately for scheduling, image pulls, node provisioning and readiness-probe delay; init is not exclusively fetch time.

Nine independent 220 MiB transfers are approximately 1.93 GiB of archive traffic. Compare them with the shared-cache arms under the measured conditions; do not infer that a node-local cache is mandatory from archive size alone. The shared-cache comparison in issue #92 remains pending until these runs are performed.

Changing the workload changes both archive size and application behavior. The matrix compares realistic **workload tiers**; it cannot by itself isolate the causal effect of archive size. It also cannot establish a universal break-even threshold. For the separate storage-size effect, use a controlled transport-only experiment and label it as such.

Before a cloud run, validate the cache against the actual archive blob and ensure that source credentials remain valid for the run. The node cache permits one HTTPS redirect only when it resolves to the exact registry origin; it rejects cross-origin redirects and redirect chains, and does not perform registry-token exchange. Record any fallback instead of counting it as a cache hit.

## GKE study readiness and registry check

The local aligned images and archives above are `linux/arm64`. The earlier GKE node was `linux/amd64`, so those archives cannot be used there. Build the immutable workload images for `linux/amd64`, then train and validate their archives on an amd64 JDK 25.0.4.1 runtime before collecting cloud measurements. Record each pushed image digest and archive SHA-256 in the cloud result directory. Train ThingsBoard on the same amd64 runtime and adapted launch configuration as its cloud measurement.

The study uses a fixed, single-node amd64 GKE Standard cluster with a 12-vCPU, 64-GB E2 node. Node autoscaling is disabled so node provisioning does not enter the pod-start measurements; the workload itself uses an HPA with min 1 and max 9 replicas. Metrics Server 0.9.1 is installed and the Metrics API is available. Keep the node fixed, and keep HPA enabled with the same configuration within a tier's four arms. Use CPU requests because the HPA target is CPU utilization. Apply a sustained, tier-calibrated request load to drive the HPA to nine, and capture the time demand begins, HPA desired/current replica changes, and the final Ready time. Report HPA reaction time separately from pod scheduling, fetch, app start, and Ready. Keep resources and probe settings matched within each tier. All test pods must land on the single node with the cache endpoint.

Keep the registry in the node's region. Pre-pull all immutable workload and tools images before measurement. Keep the database seed identical for matched arms; restore ThingsBoard to a clean snapshot between trials if startup mutates persistent state.

Run each tier through four arms: uncached start, direct per-pod archive fetch, cold node cache, and pre-populated warm node cache. Test one pod and HPA scale-out to nine pods in every arm. For the single-pod arm, hold the HPA at one replica; for scale-out, set min 1/max 9 and apply the same load profile for that tier. Reset the cache to empty immediately before a cold-cache run, and verify the expected digest is present before a warm-cache run. Repeat and interleave arm order. Record demand start, HPA desired/current replica changes, pod creation, scheduling, init start/end, archive transfer/hash start/end, app container start, and Ready timestamps. Keep image pulls, node provisioning, database restore, and probe delay separate. Summarize `uncached Ready - archive-enabled Ready` within the same tier and pod count; show HPA reaction, fetch/hash, and app start-to-Ready as separate phases.

#### Client-side event timing

Use a client-side Kubernetes watch for Pods, the Deployment and the HPA, established before triggering demand. Timestamp each received event with a local monotonic clock and a UTC wall clock; retain the object UID and the API `creationTimestamp` / Ready condition `lastTransitionTime` in raw, ignored observations. Identify pods by UID while analyzing, not by reusable pod name. Derive first-observed Ready and first-observed HPA desired/current changes from the local monotonic timeline. Keep the API timestamps alongside those observations so the watch-delivery and controller timestamp differences can be compared. Record watch errors, reconnects and restarts; a gap or missed event invalidates that round's event-derived timing. The local observation is bounded by watch delivery and probe cadence, so report those limits and the number of valid rounds. Report per-round paired differences between each archive arm and its uncached control before aggregating; do not combine rounds with different load profiles or database state.

For ThingsBoard, initialize its persistent system database with one replica before scale-out calibration or trials, then retain the same seeded database snapshot for every paired arm. A first-use nine-pod burst against an empty database produced a built-in widget-type optimistic-lock conflict in one replica. That was a bootstrap race, not representative archive-start behavior; such a round must be excluded and the cause recorded. Restore the same post-bootstrap snapshot before each measured round.

The seeded ThingsBoard load calibration on 2026-11-14 initially used 64 persistent request workers against `/login`. It produced no HTTP failures but only drove the HPA to four replicas, so it was rejected. The default datasource pool also caused PostgreSQL `too many clients` errors in the first nine-replica attempt. The benchmark Deployment now fixes the datasource pool maximum at eight connections per pod for every arm. A 128-worker verification passed: HPA first scaled at 21.5 s, desired 9 at 96.6 s, and all 9 were Ready at 147.9 s. It served 104,506 requests with zero failures over 178 sampled seconds; the last 20 seconds averaged 941.2 requests/s (range 523–1,443). The Pod, Deployment and HPA watches recorded 156 event lines without errors. Raw measurements are ignored under `artifacts/`. This load profile is validated for ThingsBoard only; it is not a four-arm benchmark result.

### Artifact Registry evidence and credential handling

A private-registry smoke check created a temporary `europe-west3` Docker repository, pushed a small test image, and requested its manifest and first blob byte using Artifact Registry's documented OAuth access-token Basic credential. The manifest returned HTTP 200 and the ranged blob returned HTTP 206 with no `Location` header. This confirms that the cache's current direct Basic-auth blob request works against a private Artifact Registry repository while the credential is valid. The test image and repository were deleted after the check. A separate anonymous request against the public Artifact Registry image `us-docker.pkg.dev/google-samples/containers/gke/hello-app:1.0` also returned the blob directly.

The node-cache source reads one Basic or Bearer authorization value from a file for each cache miss. It follows at most one same-origin HTTPS redirect and never forwards credentials to a different host. The cache verifies the fetch client's registry proof on every request, including cache hits. Google documents Artifact Registry access tokens as valid for 60 minutes, so both credentials can expire during a long repeated study. Refresh the client's registry proof and the mounted source credential before expiry. Never commit or log credentials. A `401`, rejected redirect, expired token, or direct-fetch fallback is a failed cache arm, not a cache hit.

The 128-worker ThingsBoard profile verification and its zero-failure result are recorded above. The [sanitized four-arm results](results/gke-repaired.md) include the repaired cells and measurement limitations. Do not include account email, project or resource identifiers, IP addresses, kubeconfig values, or credentials in committed benchmark data. See Google's [Artifact Registry Docker authentication guide](https://cloud.google.com/artifact-registry/docs/docker/authentication) for token lifetime and [Docker API support](https://cloud.google.com/artifact-registry/docs/reference/docker-api) for the supported registry interface. The cluster's Metrics Server uses the official [upstream release](https://github.com/kubernetes-sigs/metrics-server/releases/tag/v0.9.1), which supports Kubernetes 1.34 and newer.
