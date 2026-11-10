package io.github.baokhang83.mnemo.warmup.core;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where trained archives live between the trainer and the pods (ADR 0001). Keyed by
 * {@link ArchiveManifest#fingerprint()}. Every failure is a {@link StoreException}; callers on the
 * startup path treat it as "no archive" and start uncached (constitution §1).
 */
public interface ArchiveStore {

    /** Whether an archive for the fingerprint is stored. */
    boolean exists(String fingerprint);

    /** Stores the archive and its manifest under the manifest's own fingerprint. */
    void put(ArchiveManifest manifest, Path archiveFile);

    /**
     * Downloads the archive for the fingerprint into {@code targetDir} after verifying its digest.
     * Empty when nothing is stored.
     */
    Optional<StoredArchive> get(String fingerprint, Path targetDir);

    /** A downloaded, digest-verified archive and the manifest it was trained with. */
    record StoredArchive(ArchiveManifest manifest, Path archiveFile) {}
}
