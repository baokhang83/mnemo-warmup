package io.github.baokhang83.mnemo.warmup.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Turns the samples of each variant into a Markdown table. The first variant is the baseline. */
public final class BenchReport {

    /** All samples of one variant, in the order measured. */
    public record Entry(String variant, List<StartupBench.Sample> samples) {
        public Entry {
            if (samples.isEmpty()) {
                throw new IllegalArgumentException("No samples for " + variant);
            }
            samples = List.copyOf(samples);
        }

        /** The middle value, so one slow or fast run does not move it. Even counts average the two middle values. */
        Duration medianStartup() {
            List<Long> millis = samples.stream().map(s -> s.startup().toNanos()).sorted().toList();
            int n = millis.size();
            long nanos = n % 2 == 1 ? millis.get(n / 2) : (millis.get(n / 2 - 1) + millis.get(n / 2)) / 2;
            return Duration.ofNanos(nanos);
        }

        long medianRssKb() {
            List<Long> values = samples.stream().map(StartupBench.Sample::peakRssKb).sorted().toList();
            int n = values.size();
            return n % 2 == 1 ? values.get(n / 2) : (values.get(n / 2 - 1) + values.get(n / 2)) / 2;
        }

        Duration min() {
            return samples.stream().map(StartupBench.Sample::startup).min(Comparator.naturalOrder()).orElseThrow();
        }

        Duration max() {
            return samples.stream().map(StartupBench.Sample::startup).max(Comparator.naturalOrder()).orElseThrow();
        }
    }

    private BenchReport() {}

    public static String markdown(List<Entry> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Nothing to report");
        }
        double baseline = entries.get(0).medianStartup().toNanos();
        List<String> lines = new ArrayList<>();
        lines.add("| Variant | Startup median (ms) | Min-max (ms) | Saved vs " + entries.get(0).variant() + " | RSS (MB) |");
        lines.add("|---|---:|---:|---:|---:|");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            double median = e.medianStartup().toNanos();
            String saved = i == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", (baseline - median) / baseline * 100);
            long rss = e.medianRssKb();
            lines.add(String.format(Locale.ROOT, "| %s | %.0f | %.0f-%.0f | %s | %s |",
                    e.variant(), median / 1e6, e.min().toNanos() / 1e6, e.max().toNanos() / 1e6, saved,
                    rss == 0 ? "n/a" : String.format(Locale.ROOT, "%.0f", rss / 1024.0)));
        }
        return String.join("\n", lines);
    }
}
