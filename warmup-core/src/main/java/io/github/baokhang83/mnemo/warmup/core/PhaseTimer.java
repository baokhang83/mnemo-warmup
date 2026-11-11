package io.github.baokhang83.mnemo.warmup.core;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Optional timing of the phases of {@code fetch} and {@code run}, for working out where pod start-up time goes. It does
 * nothing unless {@code MNEMO_WARMUP_TIMING=1}, so normal logs are unchanged. Output is one line,
 * {@code timing: phase=N ms, ...}, ending with this tool JVM's own uptime (JVM start plus everything before the line).
 */
public final class PhaseTimer {

    public static final String ENV = "MNEMO_WARMUP_TIMING";

    private final boolean enabled;
    private final List<String> parts = new ArrayList<>();
    private long last = System.nanoTime();

    private PhaseTimer(boolean enabled) {
        this.enabled = enabled;
    }

    public static PhaseTimer from(Map<String, String> environment) {
        return new PhaseTimer("1".equals(environment.get(ENV)));
    }

    /** Records the time since the previous lap (or since creation) under {@code phase}. */
    public void lap(String phase) {
        if (!enabled) {
            return;
        }
        long now = System.nanoTime();
        parts.add(phase + "=" + (now - last) / 1_000_000 + " ms");
        last = now;
    }

    /** The timing line, or empty when timing is off. */
    public Optional<String> summary() {
        if (!enabled) {
            return Optional.empty();
        }
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        return Optional.of("timing: " + String.join(", ", parts) + ", tool-jvm-uptime=" + uptime + " ms");
    }
}
