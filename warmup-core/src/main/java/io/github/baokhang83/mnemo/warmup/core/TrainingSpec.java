package io.github.baokhang83.mnemo.warmup.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything needed for one training run.
 *
 * @param java          the target JDK's {@code java} launcher
 * @param strategy      AppCDS or AOT
 * @param archive       where the archive is written
 * @param jvmArgs       extra JVM options, placed before the application arguments
 * @param appArgs       the application part of the command line, e.g. {@code -jar app.jar}
 * @param environment   extra environment variables
 * @param workDir       working directory, or {@code null} to inherit
 * @param logFile       receives the application's stdout and stderr
 * @param timeout       how long the {@link ReadyCondition} may take
 * @param shutdownGrace how long the JVM gets to exit after SIGTERM; AOT creation assembles the
 *                      cache during exit and can take a while on large applications
 * @param ready         when to stop the application
 */
public record TrainingSpec(
        Path java,
        CacheStrategy strategy,
        Path archive,
        List<String> jvmArgs,
        List<String> appArgs,
        Map<String, String> environment,
        Path workDir,
        Path logFile,
        Duration timeout,
        Duration shutdownGrace,
        ReadyCondition ready) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofMinutes(2);

    public TrainingSpec {
        Objects.requireNonNull(java, "java");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(ready, "ready");
        jvmArgs = List.copyOf(jvmArgs);
        appArgs = List.copyOf(appArgs);
        environment = Map.copyOf(environment);
        if (appArgs.isEmpty()) {
            throw new IllegalArgumentException("appArgs must say what to run, e.g. -jar app.jar");
        }
        if (timeout.isNegative() || timeout.isZero() || shutdownGrace.isNegative() || shutdownGrace.isZero()) {
            throw new IllegalArgumentException("timeout and shutdownGrace must be positive");
        }
    }

    public static Builder builder(Path java, CacheStrategy strategy, Path archive) {
        return new Builder(java, strategy, archive);
    }

    public static final class Builder {
        private final Path java;
        private final CacheStrategy strategy;
        private final Path archive;
        private final List<String> jvmArgs = new ArrayList<>();
        private List<String> appArgs = List.of();
        private Map<String, String> environment = Map.of();
        private Path workDir;
        private Path logFile;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Duration shutdownGrace = DEFAULT_SHUTDOWN_GRACE;
        private ReadyCondition ready = ReadyCondition.processExits();

        private Builder(Path java, CacheStrategy strategy, Path archive) {
            this.java = java;
            this.strategy = strategy;
            this.archive = archive;
        }

        public Builder jvmArg(String arg) {
            jvmArgs.add(arg);
            return this;
        }

        public Builder appArgs(String... args) {
            this.appArgs = List.of(args);
            return this;
        }

        public Builder environment(Map<String, String> environment) {
            this.environment = environment;
            return this;
        }

        public Builder workDir(Path workDir) {
            this.workDir = workDir;
            return this;
        }

        public Builder logFile(Path logFile) {
            this.logFile = logFile;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder shutdownGrace(Duration shutdownGrace) {
            this.shutdownGrace = shutdownGrace;
            return this;
        }

        public Builder ready(ReadyCondition ready) {
            this.ready = ready;
            return this;
        }

        public TrainingSpec build() {
            Path log = logFile != null ? logFile : archive.resolveSibling(archive.getFileName() + ".training.log");
            return new TrainingSpec(
                    java, strategy, archive, jvmArgs, appArgs, environment, workDir, log, timeout, shutdownGrace, ready);
        }
    }
}
