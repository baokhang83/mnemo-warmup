package io.github.baokhang83.mnemo.warmup.cli;

import io.github.baokhang83.mnemo.warmup.core.CacheStrategy;
import io.github.baokhang83.mnemo.warmup.core.CacheType;
import io.github.baokhang83.mnemo.warmup.core.JarExtractor;
import io.github.baokhang83.mnemo.warmup.core.JdkInfo;
import java.nio.file.Path;
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

    @Command(name = "build", description = "Run a training run and produce the archive + manifest.")
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

        @Override
        public Integer call() {
            var out = spec.commandLine().getOut();
            var err = spec.commandLine().getErr();
            try {
                JdkInfo jdk = JdkInfo.detect(javaHome, System.getenv());
                CacheStrategy strategy = CacheType.parse(cacheType).resolve(jdk);
                out.printf("Target JDK: %s %s (feature %d)%n", jdk.vendor(), jdk.version(), jdk.featureVersion());
                out.printf("Cache: %s (.%s)%n", strategy, strategy.extension());
                if (jar != null) {
                    var app = new JarExtractor().prepare(jar, jdk, workDir.resolve("app"));
                    out.printf("Application: %s -> %s%n", app.kind(), app.runnableJar());
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                err.println("error: " + e.getMessage());
                return 2;
            }
            err.println("build: training is not implemented yet");
            return 0;
        }
    }

    @Command(name = "bench", description = "Compare cold starts with and without the archive.")
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
