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

    /** What {@link #inspect} found. */
    enum Presence {
        /** Nothing is stored under the fingerprint. */
        ABSENT,
        /** An entry exists and is complete and consistent. */
        USABLE,
        /** An entry exists but cannot be used (a missing or wrong blob, a manifest for another fingerprint, ...). */
        BROKEN
    }

    record Inspection(Presence presence, String detail) {
        public static Inspection absent() {
            return new Inspection(Presence.ABSENT, "nothing stored");
        }

        public static Inspection usable() {
            return new Inspection(Presence.USABLE, "complete");
        }

        public static Inspection broken(String why) {
            return new Inspection(Presence.BROKEN, why);
        }
    }

    /**
     * Whether the fingerprint has a COMPLETE entry, without downloading the archive. A tag that merely exists is not
     * enough: a trainer that treats it as done would never repair a half-written or damaged entry. The default only
     * knows presence; stores that can look inside should override it.
     *
     * @throws StoreException if the store cannot be asked
     */
    default Inspection inspect(String fingerprint) {
        return exists(fingerprint) ? Inspection.usable() : Inspection.absent();
    }

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
