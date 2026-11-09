package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Measures how long an application takes to start, as a fresh JVM process, with or without an archive.
 *
 * <p>"Started" is whatever the {@link ExitMode}'s {@link ReadyCondition} says, so every variant is
 * stopped at the same moment. The clock runs from just before the process is launched until the
 * condition is met. Pass a mode without warmup traffic, or the warmup is counted as startup.
 */
public final class StartupBench {

    /**
     * One thing to measure.
     *
     * @param name     the label in the report
     * @param strategy how the archive is passed to the JVM; {@code null} for no archive
     * @param archive  the archive file; {@code null} for no archive
     */
    public record Variant(String name, CacheStrategy strategy, Path archive) {

        public static Variant none() {
            return new Variant("none", null, null);
        }

        public static Variant of(String name, CacheStrategy strategy, Path archive) {
            return new Variant(name, strategy, archive);
        }
    }

    /**
     * @param startup    time from launching the process until it was ready
     * @param peakRssKb  highest resident memory seen while it ran, in KiB; {@code 0} if it could not be read.
     *                   It is sampled, so it can miss a short peak (and the final one when the app exits at once).
     */
    public record Sample(Duration startup, long peakRssKb) {}

    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

    private final MemoryProbe memory;

    public StartupBench() {
        this(MemoryProbe.system());
    }

    StartupBench(MemoryProbe memory) {
        this.memory = memory;
    }

    /**
     * Starts the application {@code warmups + runs} times and returns the last {@code runs} samples.
     * The first {@code warmups} starts are thrown away: they pay one-off costs such as reading the
     * files from disk, which the later starts do not.
     *
     * @throws TrainingException if the application fails or is not ready within {@code timeout}
     */
    public List<Sample> measure(
            JdkInfo jdk,
            Variant variant,
            JarExtractor.ExtractedApp app,
            ExitMode mode,
            List<String> jvmArgs,
            Duration timeout,
            int warmups,
            int runs,
            Path logFile) {
        if (runs < 1 || warmups < 0) {
            throw new IllegalArgumentException("runs must be at least 1 and warmups at least 0");
        }
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < warmups + runs; i++) {
            Sample sample = runOnce(jdk, variant, app, mode, jvmArgs, timeout, logFile);
            if (i >= warmups) {
                samples.add(sample);
            }
        }
        return samples;
    }

    List<String> command(JdkInfo jdk, Variant variant, JarExtractor.ExtractedApp app, ExitMode mode, List<String> jvmArgs) {
        List<String> command = new ArrayList<>();
        command.add(jdk.javaExecutable().toString());
        if (variant.strategy() != null) {
            command.add(variant.strategy().runtimeFlag(variant.archive().toAbsolutePath().toString()));
        }
        command.addAll(jvmArgs);
        command.addAll(mode.jvmArgs());
        command.add("-jar");
        command.add(app.runnableJar().toString());
        return command;
    }

    private Sample runOnce(
            JdkInfo jdk, Variant variant, JarExtractor.ExtractedApp app, ExitMode mode, List<String> jvmArgs,
            Duration timeout, Path logFile) {
        ProcessBuilder builder = new ProcessBuilder(command(jdk, variant, app, mode, jvmArgs))
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        Process process;
        long start;
        try {
            Files.createDirectories(logFile.toAbsolutePath().getParent());
            start = System.nanoTime();
            process = builder.start();
        } catch (IOException e) {
            throw new TrainingException("Cannot start " + jdk.javaExecutable() + ": " + e.getMessage(), logFile, e);
        }
        try (MemoryProbe.Sampler sampler = memory.sample(process)) {
            long ready;
            try {
                mode.readyCondition().await(process, timeout);
                ready = System.nanoTime();
            } catch (TimeoutException e) {
                throw new TrainingException(variant.name() + ": not ready within " + timeout + ": " + e.getMessage(), logFile, e);
            }
            if (!process.isAlive() && process.exitValue() != 0) {
                throw new TrainingException(
                        variant.name() + ": application exited with code " + process.exitValue() + " before it was ready", logFile);
            }
            return new Sample(Duration.ofNanos(ready - start), sampler.peakKb());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrainingException(variant.name() + ": interrupted", logFile, e);
        } finally {
            stop(process);
        }
    }

    private static void stop(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
