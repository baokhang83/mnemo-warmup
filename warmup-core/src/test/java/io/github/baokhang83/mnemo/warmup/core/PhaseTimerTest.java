package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PhaseTimerTest {

    @Test
    void isSilentUnlessEnabled() {
        var off = PhaseTimer.from(Map.of());
        off.lap("a");
        assertTrue(off.summary().isEmpty());
        var wrongValue = PhaseTimer.from(Map.of(PhaseTimer.ENV, "true"));
        wrongValue.lap("a");
        assertTrue(wrongValue.summary().isEmpty(), "only the exact value 1 turns it on");
    }

    @Test
    void reportsEachPhaseAndTheToolJvmUptime() {
        var timer = PhaseTimer.from(Map.of(PhaseTimer.ENV, "1"));
        timer.lap("first");
        timer.lap("second");
        String line = timer.summary().orElseThrow();
        assertTrue(line.matches("timing: first=\\d+ ms, second=\\d+ ms, tool-jvm-uptime=\\d+ ms"), line);
    }
}
