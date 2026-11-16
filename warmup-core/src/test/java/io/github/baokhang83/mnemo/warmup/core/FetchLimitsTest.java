package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FetchLimitsTest {

    @Test
    void defaultsApplyWhenNothingIsSet() {
        List<String> notes = new ArrayList<>();
        var limits = FetchLimits.fromEnvironment(Map.of(), notes::add);
        assertEquals(Duration.ofSeconds(120), limits.deadline());
        assertEquals(1L << 30, limits.maxArchiveBytes());
        assertTrue(notes.isEmpty());
    }

    @Test
    void validValuesAreUsed() {
        List<String> notes = new ArrayList<>();
        var limits = FetchLimits.fromEnvironment(
                Map.of(FetchLimits.ENV_DEADLINE, "30", FetchLimits.ENV_MAX_ARCHIVE, "2048"), notes::add);
        assertEquals(Duration.ofSeconds(30), limits.deadline());
        assertEquals(2048, limits.maxArchiveBytes());
        assertTrue(notes.isEmpty());
    }

    // a bad setting must never stop an application: it is ignored with a note and the default applies
    @Test
    void anythingElseIsIgnoredWithANote() {
        for (String bad : List.of("", "0", "-5", "abc", "1.5", "1e3", "007", "+5", " 5", "99999999999999999999")) {
            List<String> notes = new ArrayList<>();
            var limits = FetchLimits.fromEnvironment(Map.of(FetchLimits.ENV_DEADLINE, bad, FetchLimits.ENV_MAX_ARCHIVE, bad), notes::add);
            assertEquals(Duration.ofSeconds(120), limits.deadline(), bad);
            assertEquals(1L << 30, limits.maxArchiveBytes(), bad);
            assertEquals(2, notes.size(), bad);
        }
    }

    @Test
    void aDeadlineOverADayIsIgnoredWhileTheSameNumberIsAFineByteCount() {
        List<String> notes = new ArrayList<>();
        var limits = FetchLimits.fromEnvironment(
                Map.of(FetchLimits.ENV_DEADLINE, "99999999", FetchLimits.ENV_MAX_ARCHIVE, "99999999"), notes::add);
        assertEquals(Duration.ofSeconds(120), limits.deadline());
        assertEquals(99_999_999L, limits.maxArchiveBytes());
        assertEquals(1, notes.size());
    }

    @Test
    void theDeadlineMessageNamesTheSettingAndSaysThePodStartsUncached() {
        String message = new FetchLimits(Duration.ofSeconds(7), 1).deadlineMessage();
        assertTrue(message.contains("total deadline of 7 s") && message.contains(FetchLimits.ENV_DEADLINE) && message.contains("without a cache"), message);
    }
}
