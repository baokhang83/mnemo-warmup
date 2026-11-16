package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Publishes a file left by an isolated training step. The caller supplies a manifest describing the
 * intended workload, not metadata supplied by the training step. Publication rechecks the store
 * under the lease and computes the archive's size and digest from a private copy before uploading.
 *
 * <p>Signing establishes who published the bytes; it does not establish that an archive produced by
 * an untrusted application or JDK is safe to execute.
 */
public final class IsolatedArchivePublisher {

    private final long openTimeoutMillis;
    private final long copyTimeoutMillis;

    public IsolatedArchivePublisher() { this(5_000, 600_000); }

    /** Bounds the open (a named pipe blocks it) and the copy (a slow or endless producer). */
    IsolatedArchivePublisher(long openTimeoutMillis, long copyTimeoutMillis) {
        if (openTimeoutMillis <= 0 || copyTimeoutMillis <= 0) throw new IllegalArgumentException("timeouts must be positive");
        this.openTimeoutMillis = openTimeoutMillis;
        this.copyTimeoutMillis = copyTimeoutMillis;
    }

    /**
     * Copies at most {@code max} bytes of {@code file} into {@code out}, without following a symlink. The open and the copy run on a
     * daemon reader thread with time limits: a path swapped for a named pipe after the type check would block an open forever, and
     * Java cannot open a file without blocking. A reader left blocked is abandoned; it can never write to {@code out} afterwards.
     */
    long copyBounded(Path file, java.io.OutputStream out, long max, String what) throws IOException {
        var opened = new java.util.concurrent.CompletableFuture<Boolean>();
        var done = new java.util.concurrent.CompletableFuture<Long>();
        var buffer = new java.util.concurrent.SynchronousQueue<byte[]>();
        var abandoned = new java.util.concurrent.atomic.AtomicBoolean();
        if (!RunPlanner.startReader(() -> {
            try (var in = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                opened.complete(true);
                long total = 0;
                byte[] chunk = new byte[64 * 1024];
                for (int n; (n = in.read(chunk)) != -1; ) {
                    if (n > max - total) throw new IOException(what + " exceeds the maximum size");
                    total += n;
                    // hand over a copy, so this thread never touches the caller's stream
                    while (!buffer.offer(java.util.Arrays.copyOf(chunk, n), 100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        if (abandoned.get()) return;
                    }
                }
                done.complete(total);
            } catch (IOException | RuntimeException e) {
                opened.complete(false);
                done.completeExceptionally(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                done.completeExceptionally(e);
            }
        })) {
            throw new IOException("too many earlier reads of the handoff are still blocked");
        }
        try {
            if (!opened.get(openTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                try { done.get(); } catch (java.util.concurrent.ExecutionException e) {
                    throw new IOException(what + " cannot be read: " + e.getCause().getMessage(), e.getCause());
                }
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(copyTimeoutMillis);
            while (true) {
                byte[] chunk = buffer.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (chunk != null) { out.write(chunk); continue; }
                if (done.isDone()) {
                    try { return done.get(); } catch (java.util.concurrent.ExecutionException e) {
                        throw new IOException(what + " cannot be read: " + e.getCause().getMessage(), e.getCause());
                    }
                }
                if (System.nanoTime() > deadline) throw new IOException(what + " was not read within the time limit");
            }
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException(what + " did not open within the time limit (not a regular file?)", e);
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException(what + " cannot be read", e);
        } finally {
            abandoned.set(true);
        }
    }

    /** Checks and publishes the training output, or reports why it was not published. */
    public TrainingOrchestrator.Outcome publish(
            ArchiveManifest live, ArchiveStore store, TrainingLock lock, Path archive, long maxArchiveBytes) {
        if (maxArchiveBytes <= 0) {
            throw new IllegalArgumentException("maximum archive size must be positive");
        }
        Path[] privateDirectory = new Path[1];
        try {
            return new TrainingOrchestrator().ensure(live, store, lock, () -> {
                if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("training output is missing or is not a regular file");
                }
                Path manifestFile = archive.resolveSibling(ArchiveManifest.FILE_NAME);
                if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("training manifest is missing or is not a regular file");
                }
                var manifestCopy = new java.io.ByteArrayOutputStream();
                copyBounded(manifestFile, manifestCopy, 8L << 20, "training manifest");
                byte[] manifestBytes = manifestCopy.toByteArray();
                final ArchiveManifest trained;
                try {
                    trained = ArchiveManifest.fromJson(new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    throw new IOException("training manifest is invalid", e);
                }
                if (!trained.mismatches(live).isEmpty() || !trained.modificationTimeMismatches(live).isEmpty()
                        || !trained.locationMismatches(live).isEmpty() || trained.archive() == null
                        || !archive.getFileName().toString().equals(trained.archive().file())) {
                    throw new IOException("training manifest does not match the intended workload");
                }
                // The publisher's temporary directory is not shared with the training pod. Copying
                // before hashing and uploading prevents the producer from changing bytes between them.
                privateDirectory[0] = Files.createTempDirectory("mnemo-publish-");
                Path copy = privateDirectory[0].resolve("app." + live.cacheType().extension());
                long total;
                try (var out = Files.newOutputStream(copy, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    total = copyBounded(archive, out, maxArchiveBytes, "training output");
                }
                if (total == 0) {
                    throw new IOException("training output is empty");
                }
                ArchiveManifest.ArchiveInfo copied = live.withArchive(copy).archive();
                if (copied.sizeBytes() != trained.archive().sizeBytes() || !copied.sha256().equals(trained.archive().sha256())) {
                    throw new IOException("training archive does not match its manifest");
                }
                return copy;
            });
        } finally {
            if (privateDirectory[0] != null) {
                try (var paths = Files.walk(privateDirectory[0])) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) { /* best effort */ }
                    });
                } catch (IOException ignored) { /* best effort */ }
            }
        }
    }
}
