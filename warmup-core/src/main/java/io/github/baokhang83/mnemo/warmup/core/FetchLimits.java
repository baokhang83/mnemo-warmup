package io.github.baokhang83.mnemo.warmup.core;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Limits that keep a hostile or broken registry from stalling a pod or filling its cache volume (constitution §1, §3). The idle
 * timeout only notices silence; the deadline bounds the whole fetch however slowly bytes arrive, and the size cap refuses a huge
 * declared archive before any of it is requested. Both come from the environment, with the same names and meaning as in the Go
 * launcher; a value that is not a positive whole number is ignored (with a note), because a bad setting must never stop an application.
 *
 * @param deadline        the whole fetch must be over by then
 * @param maxArchiveBytes the largest archive the fetch will download
 */
public record FetchLimits(Duration deadline, long maxArchiveBytes) {

    public static final String ENV_DEADLINE = "MNEMO_WARMUP_FETCH_DEADLINE_SECONDS";
    public static final String ENV_MAX_ARCHIVE = "MNEMO_WARMUP_MAX_ARCHIVE_BYTES";
    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(120);
    public static final long DEFAULT_MAX_ARCHIVE_BYTES = 1L << 30;
    private static final long MAX_DEADLINE_SECONDS = 86_400;

    public static FetchLimits defaults() {
        return new FetchLimits(DEFAULT_DEADLINE, DEFAULT_MAX_ARCHIVE_BYTES);
    }

    /** Reads the two settings; each invalid one is reported to {@code note} and replaced by its default. */
    public static FetchLimits fromEnvironment(Map<String, String> env, Consumer<String> note) {
        Duration deadline = DEFAULT_DEADLINE;
        long maxBytes = DEFAULT_MAX_ARCHIVE_BYTES;
        String d = env.get(ENV_DEADLINE);
        if (d != null) {
            Long n = positiveWholeNumber(d);
            if (n != null && n <= MAX_DEADLINE_SECONDS) {
                deadline = Duration.ofSeconds(n);
            } else {
                note.accept("ignoring " + ENV_DEADLINE + "=\"" + d + "\" (want a whole number of seconds, 1 to " + MAX_DEADLINE_SECONDS
                        + "); using " + DEFAULT_DEADLINE.toSeconds() + " s");
            }
        }
        String m = env.get(ENV_MAX_ARCHIVE);
        if (m != null) {
            Long n = positiveWholeNumber(m);
            if (n != null) {
                maxBytes = n;
            } else {
                note.accept("ignoring " + ENV_MAX_ARCHIVE + "=\"" + m + "\" (want a positive whole number of bytes); using "
                        + DEFAULT_MAX_ARCHIVE_BYTES);
            }
        }
        return new FetchLimits(deadline, maxBytes);
    }

    /** The message used whenever the total deadline ends a fetch (the same words as the Go launcher's). */
    public String deadlineMessage() {
        return "fetch exceeded the total deadline of " + deadline.toSeconds() + " s (" + ENV_DEADLINE
                + "); the application will start without a cache";
    }

    /** Digits only, no sign, no leading zero, no overflow: exactly what {@code Long.toString} would print back. */
    private static Long positiveWholeNumber(String text) {
        try {
            long n = Long.parseLong(text);
            return n > 0 && Long.toString(n).equals(text) ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
