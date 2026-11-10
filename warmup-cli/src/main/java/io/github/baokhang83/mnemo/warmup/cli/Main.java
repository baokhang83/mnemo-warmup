package io.github.baokhang83.mnemo.warmup.cli;

import io.github.baokhang83.mnemo.warmup.core.CacheStrategy;
import io.github.baokhang83.mnemo.warmup.core.AppTrainer;
import io.github.baokhang83.mnemo.warmup.core.ArchiveFetcher;
import io.github.baokhang83.mnemo.warmup.core.ArchiveManifest;
import io.github.baokhang83.mnemo.warmup.core.BenchReport;
import io.github.baokhang83.mnemo.warmup.core.StartupBench;
import io.github.baokhang83.mnemo.warmup.core.CacheType;
import io.github.baokhang83.mnemo.warmup.core.ExitMode;
import io.github.baokhang83.mnemo.warmup.core.HttpProbeExitMode;
import io.github.baokhang83.mnemo.warmup.core.JarExtractor;
import io.github.baokhang83.mnemo.warmup.core.JdkInfo;
import io.github.baokhang83.mnemo.warmup.core.OciArchiveStore;
import io.github.baokhang83.mnemo.warmup.core.RegistryCredentials;
import io.github.baokhang83.mnemo.warmup.core.RunPlanner;
import io.github.baokhang83.mnemo.warmup.core.StoreException;
import io.github.baokhang83.mnemo.warmup.core.TrainingException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "mnemo-warmup",
        mixinStandardHelpOptions = true,
        version = "mnemo-warmup 0.1.0-SNAPSHOT",
        description = "Build, validate and benchmark AppCDS / Leyden AOT caches.",
        subcommands = {Main.Build.class, Main.Bench.class, Main.Push.class, Main.Fetch.class, Main.Run.class})
public class Main implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /** Options that describe the application and how to start and stop it; shared by build and bench. */
    static class AppOptions {

        @Option(
                names = "--java-home",
                description = "JDK of the target application. Defaults to $JAVA_HOME, then the JDK running this tool.")
        Path javaHome;

        @Option(names = "--jar", description = "Application JAR to prepare (Spring Boot 3.3+ fat JARs are extracted).")
        Path jar;

        @Option(
                names = "--work-dir",
                defaultValue = ".mnemo-warmup",
                description = "Where extracted files are written, under <work-dir>/app. Default: ${DEFAULT-VALUE}.")
        Path workDir;

        @Option(
                names = "--exit-mode",
                defaultValue = "spring-refresh",
                description = "How the training run ends: spring-refresh or http-probe. Default: ${DEFAULT-VALUE}.")
        String exitMode;

        @Option(
                names = "--timeout-seconds",
                defaultValue = "300",
                description = "How long the application may take to reach its ready state. Default: ${DEFAULT-VALUE}.")
        long timeoutSeconds;

        @Option(
                names = "--jvm-arg",
                description = "JVM option for the training run, e.g. --jvm-arg=-Xmx512m. Repeatable. Use the options the app will run with: the archive is only valid for them.")
        List<String> jvmArgs = new ArrayList<>();

        @Option(names = "--url", description = "http-probe: endpoint that returns 2xx when the app is ready, e.g. http://localhost:8080/actuator/health.")
        URI url;

        @Option(names = "--poll-interval-millis", defaultValue = "500", description = "http-probe: pause between probes. Default: ${DEFAULT-VALUE}.")
        long pollIntervalMillis;

        @Option(
                names = "--warmup-url",
                description = "http-probe: GET request made once the app is ready (absolute URL or a path such as /api/items). Repeatable.")
        List<String> warmupUrls = new ArrayList<>();

        @Option(
                names = "--warmup-script",
                description = "http-probe: program run after the warmup requests; gets the base URL as its argument and must exit 0.")
        Path warmupScript;
    }

    @Command(name = "build",
            mixinStandardHelpOptions = true,
            description = "Run a training run and produce the archive + manifest.")
    static class Build implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Mixin
        AppOptions opts;

        @Option(
                names = "--cache-type",
                defaultValue = "auto",
                description = "auto (AOT on JDK 25+, AppCDS otherwise), appcds or aot. Default: ${DEFAULT-VALUE}.")
        String cacheType;

        @Option(names = "--image-digest", description = "Container image digest to record in the fingerprint, e.g. sha256:abc...")
        String imageDigest;

        @Override
        public Integer call() {
            var out = spec.commandLine().getOut();
            var err = spec.commandLine().getErr();
            try {
                JdkInfo jdk = JdkInfo.detect(opts.javaHome, System.getenv());
                CacheStrategy strategy = CacheType.parse(cacheType).resolve(jdk);
                out.printf("Target JDK: %s %s (feature %d)%n", jdk.vendor(), jdk.version(), jdk.featureVersion());
                out.printf("Cache: %s (.%s)%n", strategy, strategy.extension());
                if (opts.jar == null) {
                    err.println("build: no --jar given, nothing to train");
                    return 0;
                }
                if (opts.timeoutSeconds <= 0) {
                    throw new IllegalArgumentException("--timeout-seconds must be positive");
                }
                var probe = opts.url == null
                        ? null
                        : new HttpProbeExitMode.Settings(
                                opts.url, Duration.ofMillis(opts.pollIntervalMillis), opts.warmupUrls, opts.warmupScript);
                ExitMode mode = ExitMode.parse(opts.exitMode, probe);
                var app = new JarExtractor().prepare(opts.jar, jdk, opts.workDir.resolve("app"));
                out.printf("Application: %s -> %s%n", app.kind(), app.runnableJar());

                Path archive = opts.workDir.resolve("app." + strategy.extension());
                out.printf("Training with exit mode %s ...%n", mode.name());
                var result = new AppTrainer()
                        .train(app, jdk, strategy, mode, archive, Duration.ofSeconds(opts.timeoutSeconds), opts.jvmArgs);
                out.printf(
                        "Archive: %s (%.1f MB) in %.1f s%n",
                        result.archive(), result.sizeBytes() / 1_048_576.0, result.elapsed().toMillis() / 1000.0);

                var manifest = ArchiveManifest.describe(app, jdk, strategy, opts.jvmArgs, System.getenv(), imageDigest)
                        .withArchive(result.archive());
                Path manifestFile = result.archive().resolveSibling(ArchiveManifest.FILE_NAME);
                manifest.write(manifestFile);
                out.printf("Manifest: %s%n", manifestFile);
                out.printf("Fingerprint: %s%n", manifest.fingerprint());
                return 0;
            } catch (IllegalArgumentException | IllegalStateException e) {
                err.println("error: " + e.getMessage());
                return 2;
            } catch (TrainingException e) {
                err.println("error: " + e.getMessage());
                return 1;
            }
        }
    }

    /** The store named by {@code --store}, with the login from {@code --registry-auth} or the environment, if any. */
    static OciArchiveStore openStore(String store, Path registryAuth, boolean plainHttp, long timeoutSeconds) {
        Path auth = registryAuth;
        if (auth == null && System.getenv("MNEMO_WARMUP_REGISTRY_AUTH") != null) {
            auth = Path.of(System.getenv("MNEMO_WARMUP_REGISTRY_AUTH"));
        }
        RegistryCredentials credentials = auth == null
                ? null
                : RegistryCredentials.fromDockerConfig(auth, OciArchiveStore.registryHost(store)).orElse(null);
        return OciArchiveStore.of(store, plainHttp, credentials, Duration.ofSeconds(timeoutSeconds));
    }

    @Command(name = "push",
            mixinStandardHelpOptions = true,
            description = "Upload the archive and manifest produced by build to an OCI registry, tagged by fingerprint.")
    static class Push implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Option(
                names = "--work-dir",
                defaultValue = ".mnemo-warmup",
                description = "Directory holding the archive and " + ArchiveManifest.FILE_NAME + " from build. Default: ${DEFAULT-VALUE}.")
        Path workDir;

        @Option(names = "--store", required = true, description = "Archive store: oci://host[:port]/repository.")
        String store;

        @Option(
                names = "--registry-auth",
                description = "Docker-config style JSON with the registry login (push scope). Defaults to $MNEMO_WARMUP_REGISTRY_AUTH, then anonymous.")
        Path registryAuth;

        @Option(names = "--plain-http", description = "Talk to the registry over http instead of https (local registries only).")
        boolean plainHttp;

        @Option(names = "--timeout-seconds", defaultValue = "1800", description = "Per request. Default: ${DEFAULT-VALUE}.")
        long timeoutSeconds;

        @Override
        public Integer call() {
            var out = spec.commandLine().getOut();
            var err = spec.commandLine().getErr();
            try {
                Path manifestFile = workDir.resolve(ArchiveManifest.FILE_NAME);
                if (!Files.isRegularFile(manifestFile)) {
                    err.println("error: no " + manifestFile + " (run build first)");
                    return 2;
                }
                ArchiveManifest manifest = ArchiveManifest.read(manifestFile);
                if (manifest.archive() == null) {
                    err.println("error: " + manifestFile + " does not describe an archive");
                    return 2;
                }
                Path archive = workDir.resolve(manifest.archive().file());
                if (!Files.isRegularFile(archive)) {
                    err.println("error: archive " + archive + " not found");
                    return 2;
                }
                if (timeoutSeconds <= 0) {
                    throw new IllegalArgumentException("--timeout-seconds must be positive");
                }
                var target = openStore(store, registryAuth, plainHttp, timeoutSeconds);
                out.printf("Uploading %s (%.1f MB) to %s%n", archive, manifest.archive().sizeBytes() / 1_048_576.0, store);
                target.put(manifest, archive);
                out.printf("Stored fingerprint %s as tag %s%n", manifest.fingerprint(), OciArchiveStore.tagFor(manifest.fingerprint()));
                return 0;
            } catch (IllegalArgumentException | IllegalStateException e) {
                err.println("error: " + e.getMessage());
                return 2;
            } catch (StoreException e) {
                err.println("error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "fetch",
            mixinStandardHelpOptions = true,
            description = "Init container: look up the archive for this JDK, classpath and options, verify it and write it with a flags file. "
                    + "Always exits 0 unless --strict, so a missing or bad archive never stops the pod.")
    static class Fetch implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Mixin
        AppOptions opts;

        @Option(names = "--store", description = "Archive store: oci://host[:port]/repository.")
        String store;

        @Option(
                names = "--out",
                defaultValue = ".mnemo-warmup/cache",
                description = "Directory that receives the archive, manifest and " + ArchiveFetcher.FLAGS_FILE + ". Default: ${DEFAULT-VALUE}.")
        Path out;

        @Option(
                names = "--cache-type",
                defaultValue = "auto",
                description = "auto (try AOT on JDK 25+, then AppCDS), appcds or aot. Default: ${DEFAULT-VALUE}.")
        String cacheType;

        @Option(names = "--image-digest", description = "Container image digest, as given to build.")
        String imageDigest;

        @Option(
                names = "--registry-auth",
                description = "Docker-config style JSON with the registry login (pull scope). Defaults to $MNEMO_WARMUP_REGISTRY_AUTH, then anonymous.")
        Path registryAuth;

        @Option(names = "--plain-http", description = "Talk to the registry over http instead of https (local registries only).")
        boolean plainHttp;

        @Option(names = "--store-timeout-seconds", defaultValue = "120", description = "Per registry request and download stall. Default: ${DEFAULT-VALUE}.")
        long storeTimeoutSeconds;

        @Option(names = "--strict", description = "Exit 1 on a store problem and 2 on bad arguments instead of 0. For CI and debugging only.")
        boolean strict;

        @Override
        public Integer call() {
            var o = spec.commandLine().getOut();
            var e = spec.commandLine().getErr();
            try {
                ArchiveFetcher.clear(out);
            } catch (RuntimeException ex) {
                return fail(e, "warning: " + ex.getMessage(), 1);
            }
            try {
                if (store == null || opts.jar == null) {
                    throw new IllegalArgumentException("fetch needs --store and --jar");
                }
                if (storeTimeoutSeconds <= 0) {
                    throw new IllegalArgumentException("--store-timeout-seconds must be positive");
                }
                JdkInfo jdk = JdkInfo.detect(opts.javaHome, System.getenv());
                List<CacheStrategy> order = candidates(jdk);
                // Extraction (needed to hash a Spring Boot fat JAR's libraries) goes to a fresh directory every
                // time, because JarExtractor refuses a non-empty destination and fetch must be repeatable,
                // for example after an init container restart on a shared volume. The fingerprint uses entry
                // names and content, never the extraction path.
                Path scratch;
                try {
                    Files.createDirectories(opts.workDir);
                    scratch = Files.createTempDirectory(opts.workDir, "fetch-");
                } catch (java.io.IOException io) {
                    throw new IllegalStateException("Cannot create scratch space in " + opts.workDir + ": " + io.getMessage(), io);
                }
                List<ArchiveManifest> live = new ArrayList<>();
                try {
                    var app = new JarExtractor().prepare(opts.jar, jdk, scratch.resolve("app"));
                    for (CacheStrategy strategy : order) {
                        live.add(ArchiveManifest.describe(app, jdk, strategy, opts.jvmArgs, System.getenv(), imageDigest));
                    }
                } finally {
                    deleteTree(scratch);
                }
                var archiveStore = openStore(store, registryAuth, plainHttp, storeTimeoutSeconds);
                var result = new ArchiveFetcher().fetch(archiveStore, live, out);
                result.notes().forEach(n -> e.println("fetch: " + n));
                if (result.outcome() == ArchiveFetcher.Outcome.HIT) {
                    o.printf("Archive: %s (%s), fingerprint %s%n", result.archive(), result.cacheType(), result.fingerprint());
                    o.printf("Flags: %s%n", result.flagsFile());
                    return 0;
                }
                o.println("No usable archive; the application will start without one.");
                return strict ? 1 : 0;
            } catch (IllegalArgumentException | IllegalStateException ex) {
                return fail(e, "warning: " + ex.getMessage(), 2);
            } catch (RuntimeException ex) {
                return fail(e, "warning: " + ex.getMessage(), 1);
            }
        }

        private static void deleteTree(Path root) {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (java.io.IOException | RuntimeException ignored) {
                // best effort: the directory is scratch space
            }
        }

        /**
         * Picocli rejects bad options before call() runs, so this is the fail-open path for them: it
         * withdraws any stale flags (best effort, from --out if it can be found) and exits 0 unless
         * --strict was given.
         */
        static int failOpenOnParseError(CommandLine.ParameterException ex, String[] args) {
            var err = ex.getCommandLine().getErr();
            boolean strict = java.util.Arrays.asList(args).contains("--strict");
            err.println("warning: " + ex.getMessage());
            Path outDir = Path.of(".mnemo-warmup/cache");
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--out") && i + 1 < args.length) {
                    outDir = Path.of(args[i + 1]);
                } else if (args[i].startsWith("--out=")) {
                    outDir = Path.of(args[i].substring("--out=".length()));
                }
            }
            try {
                ArchiveFetcher.clear(outDir);
            } catch (RuntimeException clearFailure) {
                err.println("warning: " + clearFailure.getMessage());
            }
            err.println("fetch: continuing without an archive" + (strict ? " (--strict: failing)" : ""));
            return strict ? 2 : 0;
        }

        static int failOpenOnUnexpectedError(Exception ex, CommandLine commandLine) {
            var err = commandLine.getErr();
            boolean strict = commandLine.getCommand() instanceof Fetch f && f.strict;
            err.println("warning: unexpected error: " + ex);
            err.println("fetch: continuing without an archive" + (strict ? " (--strict: failing)" : ""));
            return strict ? 1 : 0;
        }

        private List<CacheStrategy> candidates(JdkInfo jdk) {
            return switch (CacheType.parse(cacheType)) {
                case AUTO -> jdk.featureVersion() >= CacheStrategy.LEYDEN_MIN_FEATURE
                        ? List.of(CacheStrategy.LEYDEN_AOT, CacheStrategy.APP_CDS)
                        : List.of(CacheStrategy.APP_CDS);
                case APPCDS -> List.of(CacheStrategy.APP_CDS);
                case AOT -> List.of(CacheType.AOT.resolve(jdk));
            };
        }

        private int fail(java.io.PrintWriter err, String message, int strictExit) {
            err.println(message);
            err.println("fetch: continuing without an archive" + (strict ? " (--strict: failing)" : ""));
            return strict ? strictExit : 0;
        }
    }

    @Command(name = "run",
            mixinStandardHelpOptions = true,
            description = "Application entrypoint: start 'java ... -jar app.jar' with the fetched archive if it still matches this exact "
                    + "launch, otherwise exactly as given. Put the command after --.",
            footer = "%nExample: mnemo-warmup run --print -- java -Xmx512m -jar /app/app.jar")
    static class Run implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Option(
                names = "--cache-dir",
                defaultValue = "${MNEMO_WARMUP_CACHE_DIR:-.mnemo-warmup/cache}",
                description = "Directory written by fetch --out. Default: $MNEMO_WARMUP_CACHE_DIR, then ${DEFAULT-VALUE}.")
        Path cacheDir;

        @Option(
                names = "--image-digest",
                defaultValue = "${MNEMO_WARMUP_IMAGE_DIGEST:-}",
                description = "Container image digest, as given to build. Default: $MNEMO_WARMUP_IMAGE_DIGEST.")
        String imageDigest;

        @Option(names = "--print", description = "Print the final command, shell-quoted on one line, and exit 0, so a shell can exec it "
                + "(scripts/mnemo-warmup-exec). The JVM is then PID 1 with no wrapper process.")
        boolean print;

        @Option(names = "--spawn", description = "Start the command as a child process, forward SIGTERM to it and exit with its status. "
                + "Keeps this JVM resident (about 40-60 MB); for images without /bin/sh. This is the default without --print.")
        boolean spawn;

        @Option(
                names = "--stop-timeout-seconds",
                defaultValue = "25",
                description = "--spawn: after SIGTERM, how long to wait for the command to exit before killing it. "
                        + "Keep it below the pod's terminationGracePeriodSeconds. Default: ${DEFAULT-VALUE}.")
        long stopTimeoutSeconds;

        @Parameters(arity = "1..*", paramLabel = "COMMAND", description = "The command to start, after --.")
        List<String> command = new ArrayList<>();

        @Override
        public Integer call() {
            var err = spec.commandLine().getErr();
            if (print && spawn) {
                err.println("error: --print and --spawn are exclusive");
                return 2;
            }
            String digest = imageDigest == null || imageDigest.isBlank() ? null : imageDigest;
            var plan = new RunPlanner().plan(command, cacheDir, System.getenv(), digest);
            plan.notes().forEach(n -> err.println("run: " + n));
            return launch(spec.commandLine(), plan.command(), print, Duration.ofSeconds(Math.max(1, stopTimeoutSeconds)));
        }

        static int launch(CommandLine cmd, List<String> command, boolean print, Duration stopTimeout) {
            if (print) {
                cmd.getOut().println(shellQuote(command));
                return 0;
            }
            // The hook is registered BEFORE the child starts: a SIGTERM that arrives while the child is
            // still coming up (or while this JVM is still linking the hook) must not be lost, or this JVM
            // would exit at once and leave the child running with no grace period.
            var child = new java.util.concurrent.atomic.AtomicReference<Process>();
            var terminating = new java.util.concurrent.atomic.AtomicBoolean();
            Thread forwarder = new Thread(() -> {
                terminating.set(true);
                Process running = child.get();
                if (running != null) {
                    stop(running, stopTimeout);
                }
            }, "run-signal-forwarder");
            Runtime.getRuntime().addShutdownHook(forwarder);
            try {
                Process process = new ProcessBuilder(command).inheritIO().start();
                child.set(process);
                if (terminating.get()) {
                    // the hook ran before the child existed: stop it ourselves, and wait for it
                    stop(process, stopTimeout);
                }
                try {
                    return process.waitFor();
                } catch (InterruptedException e) {
                    process.destroy();
                    Thread.currentThread().interrupt();
                    return 130;
                }
            } catch (java.io.IOException e) {
                cmd.getErr().println("error: cannot start " + command.get(0) + ": " + e.getMessage());
                return 127;
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(forwarder);
                } catch (IllegalStateException ignored) {
                    // JVM is already shutting down
                }
            }
        }

        /**
         * Forwards SIGTERM to the child and WAITS for it. When this JVM is PID 1 it must not exit while the
         * child is still running its shutdown hooks, or the container runtime kills the child at once
         * instead of giving it the grace period. Falls back to SIGKILL after {@code timeout}.
         *
         * @return whether the child exited on its own within the timeout
         */
        static boolean stop(Process process, Duration timeout) {
            process.destroy();
            try {
                if (process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            process.destroyForcibly();
            return false;
        }

        /** {@code 'a' 'b c' 'it'\''s'}: safe for {@code eval} in any POSIX shell. */
        static String shellQuote(List<String> command) {
            var sb = new StringBuilder();
            for (String arg : command) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append('\'').append(arg.replace("'", "'\\''")).append('\'');
            }
            return sb.toString();
        }

        /**
         * A bad option to {@code run} must not stop the application: whatever follows {@code --} is still
         * the command to start, so start it unchanged (or print it, with --print).
         */
        static int failOpenOnParseError(CommandLine.ParameterException ex, String[] args) {
            var cmd = ex.getCommandLine();
            int dashes = java.util.Arrays.asList(args).indexOf("--");
            if (dashes < 0 || dashes == args.length - 1) {
                cmd.getErr().println("error: " + ex.getMessage());
                cmd.getErr().println("usage: mnemo-warmup run [--print|--spawn] -- <command>");
                return 2;
            }
            cmd.getErr().println("warning: " + ex.getMessage() + "; starting the command unchanged");
            List<String> original = List.of(args).subList(dashes + 1, args.length);
            boolean print = java.util.Arrays.asList(args).subList(0, dashes).contains("--print");
            return launch(cmd, original, print, Duration.ofSeconds(25));
        }
    }

    @Command(name = "bench",
            mixinStandardHelpOptions = true,
            description = "Compare cold starts with no archive, AppCDS and AOT, and print a Markdown table.")
    static class Bench implements Callable<Integer> {

        /** Fixed so every variant is timed with the same resolution: a reading can be late by at most this much. */
        private static final Duration MEASURE_POLL = Duration.ofMillis(20);

        @Spec
        CommandSpec spec;

        @Mixin
        AppOptions opts;

        @Option(names = "--runs", defaultValue = "3", description = "Timed cold starts per variant. Default: ${DEFAULT-VALUE}.")
        int runs;

        @Option(
                names = "--warmups",
                defaultValue = "1",
                description = "Starts per variant that are run first and thrown away. Default: ${DEFAULT-VALUE}.")
        int warmups;

        @Override
        public Integer call() {
            var out = spec.commandLine().getOut();
            var err = spec.commandLine().getErr();
            try {
                if (opts.jar == null) {
                    throw new IllegalArgumentException("bench needs --jar");
                }
                if (opts.timeoutSeconds <= 0 || runs < 1 || warmups < 0) {
                    throw new IllegalArgumentException("--timeout-seconds and --runs must be positive and --warmups not negative");
                }
                JdkInfo jdk = JdkInfo.detect(opts.javaHome, System.getenv());
                var trainProbe = opts.url == null
                        ? null
                        : new HttpProbeExitMode.Settings(
                                opts.url, Duration.ofMillis(opts.pollIntervalMillis), opts.warmupUrls, opts.warmupScript);
                ExitMode trainMode = ExitMode.parse(opts.exitMode, trainProbe);
                // Same mode for measuring, but no warmup traffic: that would be counted as startup.
                ExitMode measureMode = opts.url == null
                        ? trainMode
                        : ExitMode.parse(opts.exitMode, HttpProbeExitMode.Settings.of(opts.url)
                                .withPollInterval(MEASURE_POLL));
                var app = new JarExtractor().prepare(opts.jar, jdk, opts.workDir.resolve("app"));
                Duration timeout = Duration.ofSeconds(opts.timeoutSeconds);

                var variants = new ArrayList<StartupBench.Variant>();
                variants.add(StartupBench.Variant.none());
                for (CacheStrategy strategy : strategiesFor(jdk)) {
                    Path archive = opts.workDir.resolve("bench." + strategy.extension());
                    err.printf("Training %s ...%n", strategy);
                    new AppTrainer().train(app, jdk, strategy, trainMode, archive, timeout, opts.jvmArgs);
                    variants.add(StartupBench.Variant.of(label(strategy), strategy, archive));
                }

                var entries = new ArrayList<BenchReport.Entry>();
                for (var variant : variants) {
                    err.printf("Measuring %s (%d warmup + %d timed starts) ...%n", variant.name(), warmups, runs);
                    var samples = new StartupBench().measure(
                            jdk, variant, app, measureMode, opts.jvmArgs, timeout, warmups, runs,
                            opts.workDir.resolve("bench-" + variant.name() + ".log"));
                    entries.add(new BenchReport.Entry(variant.name(), samples));
                }
                out.printf("JDK %s %s, %d timed starts per variant (median):%n%n", jdk.vendor(), jdk.version(), runs);
                out.println(BenchReport.markdown(entries));
                return 0;
            } catch (IllegalArgumentException | IllegalStateException e) {
                err.println("error: " + e.getMessage());
                return 2;
            } catch (TrainingException e) {
                err.println("error: " + e.getMessage());
                return 1;
            }
        }

        /** AppCDS is available on every supported JDK; AOT only where the single-step flags exist. */
        static List<CacheStrategy> strategiesFor(JdkInfo jdk) {
            var list = new ArrayList<CacheStrategy>();
            list.add(CacheStrategy.APP_CDS);
            if (jdk.featureVersion() >= CacheStrategy.LEYDEN_MIN_FEATURE) {
                list.add(CacheStrategy.LEYDEN_AOT);
            }
            return list;
        }

        private static String label(CacheStrategy strategy) {
            return strategy == CacheStrategy.APP_CDS ? "appcds" : "aot";
        }
    }

    static CommandLine commandLine() {
        var cmd = new CommandLine(new Main());
        // fetch runs as an init container: a bad option or an unexpected error must not stop the pod (§3).
        var defaultParseHandler = cmd.getParameterExceptionHandler();
        cmd.setParameterExceptionHandler((ex, args) -> switch (ex.getCommandLine().getCommandName()) {
            case "fetch" -> Fetch.failOpenOnParseError(ex, args);
            case "run" -> Run.failOpenOnParseError(ex, args);
            default -> defaultParseHandler.handleParseException(ex, args);
        });
        var defaultExecutionHandler = cmd.getExecutionExceptionHandler();
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> "fetch".equals(commandLine.getCommandName())
                ? Fetch.failOpenOnUnexpectedError(ex, commandLine)
                : defaultExecutionHandler.handleExecutionException(ex, commandLine, parseResult));
        return cmd;
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }
}
