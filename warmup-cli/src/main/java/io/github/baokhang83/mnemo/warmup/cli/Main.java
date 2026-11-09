package io.github.baokhang83.mnemo.warmup.cli;

import io.github.baokhang83.mnemo.warmup.core.CacheStrategy;
import io.github.baokhang83.mnemo.warmup.core.AppTrainer;
import io.github.baokhang83.mnemo.warmup.core.CacheType;
import io.github.baokhang83.mnemo.warmup.core.ExitMode;
import io.github.baokhang83.mnemo.warmup.core.HttpProbeExitMode;
import io.github.baokhang83.mnemo.warmup.core.JarExtractor;
import io.github.baokhang83.mnemo.warmup.core.JdkInfo;
import io.github.baokhang83.mnemo.warmup.core.TrainingException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "mnemo-warmup",
        mixinStandardHelpOptions = true,
        version = "mnemo-warmup 0.1.0-SNAPSHOT",
        description = "Build, validate and benchmark AppCDS / Leyden AOT caches.",
        subcommands = {Main.Build.class, Main.Bench.class})
public class Main implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "build",
            mixinStandardHelpOptions = true,
            description = "Run a training run and produce the archive + manifest.")
    static class Build implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Option(
                names = "--java-home",
                description = "JDK of the target application. Defaults to $JAVA_HOME, then the JDK running this tool.")
        Path javaHome;

        @Option(
                names = "--cache-type",
                defaultValue = "auto",
                description = "auto (AOT on JDK 25+, AppCDS otherwise), appcds or aot. Default: ${DEFAULT-VALUE}.")
        String cacheType;

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

        @Override
        public Integer call() {
            var out = spec.commandLine().getOut();
            var err = spec.commandLine().getErr();
            try {
                JdkInfo jdk = JdkInfo.detect(javaHome, System.getenv());
                CacheStrategy strategy = CacheType.parse(cacheType).resolve(jdk);
                out.printf("Target JDK: %s %s (feature %d)%n", jdk.vendor(), jdk.version(), jdk.featureVersion());
                out.printf("Cache: %s (.%s)%n", strategy, strategy.extension());
                if (jar == null) {
                    err.println("build: no --jar given, nothing to train");
                    return 0;
                }
                if (timeoutSeconds <= 0) {
                    throw new IllegalArgumentException("--timeout-seconds must be positive");
                }
                var probe = url == null
                        ? null
                        : new HttpProbeExitMode.Settings(
                                url, Duration.ofMillis(pollIntervalMillis), warmupUrls, warmupScript);
                ExitMode mode = ExitMode.parse(exitMode, probe);
                var app = new JarExtractor().prepare(jar, jdk, workDir.resolve("app"));
                out.printf("Application: %s -> %s%n", app.kind(), app.runnableJar());

                Path archive = workDir.resolve("app." + strategy.extension());
                out.printf("Training with exit mode %s ...%n", mode.name());
                var result = new AppTrainer()
                        .train(app, jdk, strategy, mode, archive, Duration.ofSeconds(timeoutSeconds));
                out.printf(
                        "Archive: %s (%.1f MB) in %.1f s%n",
                        result.archive(), result.sizeBytes() / 1_048_576.0, result.elapsed().toMillis() / 1000.0);
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

    @Command(name = "bench",
            mixinStandardHelpOptions = true,
            description = "Compare cold starts with and without the archive.")
    static class Bench implements Runnable {
        @Spec
        CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().getErr().println("bench: not implemented yet");
        }
    }

    static CommandLine commandLine() {
        return new CommandLine(new Main());
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }
}
