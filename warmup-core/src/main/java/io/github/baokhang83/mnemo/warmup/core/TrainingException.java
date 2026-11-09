package io.github.baokhang83.mnemo.warmup.core;

import java.nio.file.Path;

/** A training run did not produce a usable archive. The message includes the tail of the app log. */
public class TrainingException extends RuntimeException {

    private final Path logFile;

    public TrainingException(String message, Path logFile) {
        super(message);
        this.logFile = logFile;
    }

    public TrainingException(String message, Path logFile, Throwable cause) {
        super(message, cause);
        this.logFile = logFile;
    }

    /** The file holding the application's full output, or {@code null} if none was written. */
    public Path logFile() {
        return logFile;
    }
}
