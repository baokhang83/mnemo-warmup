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
                Path manifestFile = archive.resolveSibling(ArchiveManifest.FILE_NAME);
                if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("training manifest is missing or is not a regular file");
                }
                byte[] manifestBytes = RunPlanner.snapshot(manifestFile, 8L << 20);
                if (manifestBytes == null) throw new IOException("training manifest cannot be read within its limits");
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
