package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs an external command to completion. Abstracted so callers can be unit tested. */
public interface CommandRunner {

    /** Exit code and the merged stdout/stderr of a finished command. */
    record CommandResult(int exitCode, String output) {}

    CommandResult run(List<String> command) throws IOException, InterruptedException;

    /** A runner that starts real processes and kills them after {@code timeout}. */
    static CommandRunner system(Duration timeout) {
        return command -> {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[][] captured = new byte[1][];
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    captured[0] = process.getInputStream().readAllBytes();
                } catch (IOException e) {
                    captured[0] = new byte[0];
                }
            });
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("Timed out after " + timeout + ": " + String.join(" ", command));
            }
            reader.join();
            return new CommandResult(process.exitValue(), new String(captured[0], StandardCharsets.UTF_8));
        };
    }
}
