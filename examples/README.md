# Example: a Spring Boot app with a shared startup cache

`spring-boot/` is a small Spring Boot 3.5 application (`/hello`, `/health`). `helm/example-app/` is a workload chart
that runs it with mnemo-warmup. Together they are what the end-to-end test (`scripts/e2e-kind.sh`) runs on a kind cluster
with JDK 21 (AppCDS) and JDK 25 (AOT).

## What to copy into your own application

**1. Extract the JAR when you build the image** (`spring-boot/Dockerfile`):

```dockerfile
RUN java -Djarmode=tools -jar target/app.jar extract --destination /out
...
COPY --from=build /out /app
```

The trainer and every replica then run the same files at the same path with the same modification times, which is what
an archive needs. Build one image per JDK you run (`--build-arg JDK=21` or `25`): the image's JDK decides the cache type
(AppCDS on 21, the AOT cache on 25).

**2. Use the chart's pod-template helpers in your Deployment** (`helm/example-app/templates/deployment.yaml`): the
two init containers, the volumes, the environment, and the exec wrapper as the container command with your normal
`java ...` command as its arguments. Put the JVM options that matter (heap size, GC) in `warmup.app.jvmArgs` and have your
Deployment use that same list, so the trainer, `fetch` and the application always agree.

**3. Give the chart a registry** for the archives (`warmup.store.url`, `oci://...`) and, for a private registry, two
`dockerconfigjson` Secrets (`warmup.store.pullSecret` for `fetch`, `warmup.store.pushSecret` for the trainer).

## Try it

```bash
scripts/e2e-kind.sh          # JDK 21 and 25; needs docker, kind, kubectl, helm
scripts/e2e-kind.sh 21       # one JDK
KEEP=1 scripts/e2e-kind.sh 21   # keep the cluster for poking around (the script prints the context name)
```

What it asserts, per JDK: the app starts and is ready with no archive; the trainer trains and uploads once, releases its
Lease, and a second run says `ALREADY_STORED`; after a restart `fetch` finds the archive, the application's classes load
from it and `java` is PID 1; and a JVM option changed only in the Deployment makes the pod start uncached, healthy, with the
reason in its log.
