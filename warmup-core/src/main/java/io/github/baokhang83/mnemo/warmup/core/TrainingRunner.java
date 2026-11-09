package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the application once with archive-recording flags, lets a {@link ReadyCondition} decide
 * when it has started up enough, stops it with SIGTERM so the JVM writes the archive on its way
 * out, and checks that an archive really exists.
 *
 * <p>Stopping by signal relies on POSIX behaviour (verified for JDK 21 AppCDS and JDK 25 AOT);
 * on Windows {@link Process#destroy()} kills the process and no archive is written.
 */
public final class TrainingRunner {

    /** Exit status of a JVM stopped by SIGTERM (128 + 15). */
    static final int EXIT_SIGTERM = 143;

    private static final int LOG_TAIL_LINES = 40;

    /**
     * @param archive    the archive that was written
     * @param sizeBytes  its size
     * @param elapsed    wall-clock time of the whole run including shutdown
     * @param stoppedByUs whether the application was stopped with SIGTERM (as opposed to exiting by itself)
     * @param logFile    the application's output
     */
    public record Result(Path archive, long sizeBytes, Duration elapsed, boolean stoppedByUs, Path logFile) {}

    /**
     * @throws TrainingException if the application fails, times out, does not stop, or leaves no archive;
     *                           in that case no archive is left behind
     */
    public Result run(TrainingSpec spec) {
        try {
            return runOnce(spec);
        } catch (TrainingException e) {
            // A failed run (even one that still wrote a file, e.g. after System.exit(3)) must not
            // leave an archive that a later step could mistake for a good one.
            deleteQuietly(spec.archive());
            throw e;
        }
    }

    private Result runOnce(TrainingSpec spec) {
        long started = System.nanoTime();
        Path log = spec.logFile();
        Path leftoverConfig = spec.archive().resolveSibling(spec.archive().getFileName() + ".config");
        prepareFiles(spec, log, leftoverConfig);

        Process process = start(spec, log);
        boolean stoppedByUs = false;
        try {
            try {
                spec.ready().await(process, spec.timeout());
            } catch (TimeoutException e) {
                terminate(process, spec.shutdownGrace());
                throw new TrainingException(
                        "Training timed out after " + spec.timeout() + ": " + e.getMessage() + tail(log), log, e);
            }
            if (process.isAlive()) {
                stoppedByUs = true;
                process.destroy(); // SIGTERM: the JVM runs its exit path, which writes the archive
                if (!process.waitFor(spec.shutdownGrace().toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new TrainingException(
                            "Application did not exit within " + spec.shutdownGrace()
                                    + " after SIGTERM; the archive would be incomplete" + tail(log),
                            log);
                }
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new TrainingException("Training was interrupted", log, e);
        } finally {
            deleteQuietly(leftoverConfig);
        }

        int exit = process.exitValue();
        boolean acceptable = exit == 0 || (stoppedByUs && exit == EXIT_SIGTERM);
        if (!acceptable) {
            throw new TrainingException("Application exited with code " + exit + " during training" + tail(log), log);
        }
        long size = sizeOf(spec.archive());
        if (size <= 0) {
            throw new TrainingException(
                    "Training finished (exit " + exit + ") but no archive was written to " + spec.archive()
                            + "; the JVM only writes it on a normal exit" + tail(log),
                    log);
        }
        return new Result(spec.archive(), size, Duration.ofNanos(System.nanoTime() - started), stoppedByUs, log);
    }

    List<String> command(TrainingSpec spec) {
        List<String> command = new ArrayList<>();
        command.add(spec.java().toString());
        command.add(spec.strategy().trainingFlag(spec.archive().toAbsolutePath().toString()));
        command.addAll(spec.jvmArgs());
        command.addAll(spec.appArgs());
        return command;
    }

    private Process start(TrainingSpec spec, Path log) {
        ProcessBuilder builder = new ProcessBuilder(command(spec))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().putAll(spec.environment());
        if (spec.workDir() != null) {
            builder.directory(spec.workDir().toFile());
        }
        try {
            return builder.start();
        } catch (IOException e) {
            throw new TrainingException("Cannot start " + spec.java() + ": " + e.getMessage(), log, e);
        }
    }

    private static void prepareFiles(TrainingSpec spec, Path log, Path leftoverConfig) {
        try {
            for (Path dir : new Path[] {log.toAbsolutePath().getParent(), spec.archive().toAbsolutePath().getParent()}) {
                Files.createDirectories(dir);
            }
            // A stale archive from an earlier run must never be mistaken for this run's output.
            Files.deleteIfExists(spec.archive());
            Files.deleteIfExists(leftoverConfig);
        } catch (IOException e) {
            throw new TrainingException("Cannot prepare training files: " + e.getMessage(), log, e);
        }
    }

    private static void terminate(Process process, Duration grace) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static String tail(Path log) {
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - LOG_TAIL_LINES);
            return "\n--- last " + (lines.size() - from) + " lines of " + log + " ---\n"
                    + String.join("\n", lines.subList(from, lines.size()));
        } catch (IOException | RuntimeException e) {
            return "\n(no readable application log at " + log + ")";
        }
    }
}
