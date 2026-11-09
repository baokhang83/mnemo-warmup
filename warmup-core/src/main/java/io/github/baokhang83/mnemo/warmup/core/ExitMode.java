package io.github.baokhang83.mnemo.warmup.core;

import java.util.List;
import java.util.Locale;

/**
 * How a training run ends: which JVM options to add and what tells us the application has
 * started up enough to be stopped.
 */
public interface ExitMode {

    /** The name used on the command line, e.g. {@code spring-refresh}. */
    String name();

    /** Extra JVM options for the training run. */
    List<String> jvmArgs();

    /** When to stop the application. */
    ReadyCondition readyCondition();

    /** Whether this mode can drive the given kind of application. */
    boolean supports(JarExtractor.Kind kind);

    /** Resolves a command-line name for modes that need no settings. */
    static ExitMode parse(String name) {
        return parse(name, null);
    }

    /**
     * Resolves a command-line name.
     *
     * @param probe settings for {@code http-probe}; may be {@code null} for the other modes
     * @throws IllegalArgumentException for an unknown name, or {@code http-probe} without settings
     */
    static ExitMode parse(String name, HttpProbeExitMode.Settings probe) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals(SpringRefreshExitMode.NAME)) {
            return new SpringRefreshExitMode();
        }
        if (normalized.equals(HttpProbeExitMode.NAME)) {
            if (probe == null) {
                throw new IllegalArgumentException("Exit mode 'http-probe' requires --url");
            }
            return new HttpProbeExitMode(probe);
        }
        throw new IllegalArgumentException("Unknown exit mode '" + name + "'; expected: "
                + SpringRefreshExitMode.NAME + ", " + HttpProbeExitMode.NAME);
    }
}
