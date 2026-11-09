package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Reads the resident memory (RSS) of a running process. On Linux it reads {@code VmHWM}, the kernel's
 * own peak, from {@code /proc}; elsewhere it polls {@code ps -o rss=} and keeps the highest value.
 */
interface MemoryProbe {

    /** Starts watching {@code process}; close the result to stop. */
    Sampler sample(Process process);

    interface Sampler extends AutoCloseable {
        /** Highest RSS seen so far in KiB, or 0 if none could be read. */
        long peakKb();

        @Override
        void close();
    }

    long POLL_MILLIS = 20;

    static MemoryProbe system() {
        return process -> new PollingSampler(process.pid());
    }

    /** Polls on a daemon thread until closed. */
    final class PollingSampler implements Sampler {
        private volatile long peak;
        private volatile boolean running = true;
        private final Thread thread;

        PollingSampler(long pid) {
            thread = new Thread(() -> {
                while (running) {
                    peak = Math.max(peak, readKb(pid));
                    try {
                        Thread.sleep(POLL_MILLIS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "rss-sampler-" + pid);
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public long peakKb() {
            return peak;
        }

        @Override
        public void close() {
            running = false;
            thread.interrupt();
        }
    }

    /** RSS of {@code pid} in KiB, or 0 if it cannot be read (for example the process just ended). */
    static long readKb(long pid) {
        Path status = Path.of("/proc", Long.toString(pid), "status");
        if (Files.isReadable(status)) {
            try {
                for (String line : Files.readAllLines(status)) {
                    if (line.startsWith("VmHWM:")) {
                        return Long.parseLong(line.replaceAll("\\D+", ""));
                    }
                }
            } catch (IOException | NumberFormatException e) {
                return 0;
            }
            return 0;
        }
        try {
            Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(pid))
                    .redirectErrorStream(true).start();
            String out = new String(ps.getInputStream().readAllBytes()).trim();
            ps.waitFor(2, TimeUnit.SECONDS);
            return out.isEmpty() ? 0 : Long.parseLong(out);
        } catch (IOException | NumberFormatException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }
}
