package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warmup.core.BenchReport.Entry;
import io.github.baokhang83.mnemo.warmup.core.StartupBench.Sample;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class BenchReportTest {

    private static Sample s(long ms, long rssKb) {
        return new Sample(Duration.ofMillis(ms), rssKb);
    }

    @Test
    void medianIgnoresOneSlowRun() {
        Entry e = new Entry("none", List.of(s(1000, 0), s(5000, 0), s(1100, 0)));
        assertEquals(Duration.ofMillis(1100), e.medianStartup());
    }

    @Test
    void evenCountAveragesTheMiddlePair() {
        Entry e = new Entry("none", List.of(s(100, 0), s(200, 0), s(300, 0), s(900, 0)));
        assertEquals(Duration.ofMillis(250), e.medianStartup());
    }

    @Test
    void tableShowsSavingsAgainstTheFirstVariant() {
        String table = BenchReport.markdown(List.of(
                new Entry("none", List.of(s(2000, 204_800), s(2000, 204_800), s(2000, 204_800))),
                new Entry("aot", List.of(s(800, 307_200), s(900, 307_200), s(1000, 307_200)))));

        String[] lines = table.split("\n");
        assertEquals("| Variant | Startup median (ms) | Min-max (ms) | Saved vs none | RSS (MB) |", lines[0]);
        assertEquals("| none | 2000 | 2000-2000 | - | 200 |", lines[2]);
        assertEquals("| aot | 900 | 800-1000 | 55.0% | 300 |", lines[3]);
    }

    @Test
    void unreadableMemoryShowsAsNotAvailable() {
        String table = BenchReport.markdown(List.of(new Entry("none", List.of(s(100, 0)))));
        assertTrue(table.contains("| n/a |"), table);
    }

    @Test
    void inputsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new Entry("none", List.of()));
        assertThrows(IllegalArgumentException.class, () -> BenchReport.markdown(List.of()));
    }
}
