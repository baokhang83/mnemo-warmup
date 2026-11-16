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
                // The publisher's temporary directory is not shared with the training pod. Copying
                // before hashing and uploading prevents the producer from changing bytes between them.
                privateDirectory[0] = Files.createTempDirectory("mnemo-publish-");
                Path copy = privateDirectory[0].resolve("app." + live.cacheType().extension());
                long total = 0;
                try (var in = Files.newInputStream(archive, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                     var out = Files.newOutputStream(copy, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int n; (n = in.read(buffer)) != -1; ) {
                        if (n > maxArchiveBytes - total) {
                            throw new IOException("training output exceeds the maximum archive size");
                        }
                        out.write(buffer, 0, n);
                        total += n;
                    }
                }
                if (total == 0) {
                    throw new IOException("training output is empty");
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
