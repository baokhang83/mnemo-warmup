package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Publishes a file left by an isolated training step. The caller supplies a manifest describing the
 * intended workload, not metadata supplied by the training step. Publication rechecks the store
 * under the lease and computes the archive's size and digest from the file before uploading it.
 *
 * <p>Signing establishes who published the bytes; it does not establish that an archive produced by
 * an untrusted application or JDK is safe to execute.
 */
public final class IsolatedArchivePublisher {

    /**
     * Checks and publishes the training output, or reports why it was not published. The output
     * directory must be inaccessible to other writers while this method runs.
     */
    public TrainingOrchestrator.Outcome publish(
            ArchiveManifest live, ArchiveStore store, TrainingLock lock, Path archive, long maxArchiveBytes) {
        if (maxArchiveBytes <= 0) {
            throw new IllegalArgumentException("maximum archive size must be positive");
        }
        return new TrainingOrchestrator().ensure(live, store, lock, () -> {
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("training output is missing or is not a regular file");
            }
            long size = Files.size(archive);
            if (size <= 0 || size > maxArchiveBytes) {
                throw new IOException("training output size is outside the allowed range");
            }
            return archive;
        });
    }
}
