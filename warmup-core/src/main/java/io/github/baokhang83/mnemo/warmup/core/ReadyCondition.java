package io.github.baokhang83.mnemo.warmup.core;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decides when a training run has seen enough and the application should be stopped so the
 * JVM writes its archive. Exit modes (Spring refresh, HTTP probe, ...) are implementations.
 */
@FunctionalInterface
public interface ReadyCondition {

    /**
     * Blocks until the application is ready to be stopped, or has already exited.
     *
     * @param process the running application
     * @param timeout the longest the caller is willing to wait
     * @throws TimeoutException if the condition was not met within {@code timeout}
     */
    void await(Process process, Duration timeout) throws TimeoutException, InterruptedException;

    /** The application ends by itself (for example Spring's {@code spring.context.exit=onRefresh}). */
    static ReadyCondition processExits() {
        return (process, timeout) -> {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Application did not exit within " + timeout);
            }
        };
    }

    /** Let the application run for a fixed time, or until it exits, whichever comes first. */
    static ReadyCondition afterDelay(Duration delay) {
        return (process, timeout) -> process.waitFor(Math.min(delay.toMillis(), timeout.toMillis()), TimeUnit.MILLISECONDS);
    }
}
