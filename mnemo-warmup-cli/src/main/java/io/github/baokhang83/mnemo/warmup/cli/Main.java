package io.github.baokhang83.mnemo.warmup.cli;

import io.github.baokhang83.mnemo.warmup.core.CacheStrategy;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "mnemo-warmup",
        mixinStandardHelpOptions = true,
        version = "mnemo-warmup 0.1.0-SNAPSHOT",
        description = "Build, validate and benchmark AppCDS / Leyden AOT caches.",
        subcommands = {Main.Build.class, Main.Bench.class})
public class Main implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    @Command(name = "build", description = "Run a training run and produce the archive + manifest.")
    static class Build implements Runnable {
        @Override
        public void run() {
            int feature = Runtime.version().feature();
            System.out.println("Detected JDK " + feature + " -> " + CacheStrategy.forFeatureVersion(feature));
            System.err.println("build: not implemented yet");
        }
    }

    @Command(name = "bench", description = "Compare cold starts with and without the archive.")
    static class Bench implements Runnable {
        @Override
        public void run() {
            System.err.println("bench: not implemented yet");
        }
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
