package io.github.baokhang83.mnemo.warmup.core;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The trainer's whole decision, in the order ADR 0002 gives: skip if the store already has the archive,
 * otherwise take the lock, check again, train, upload, release. A tag that exists but whose entry is incomplete
 * (a missing blob, a manifest for another fingerprint) counts as absent and is replaced, so a damaged entry is
 * repaired instead of being reported as done forever. It never throws: every failure is an
 * {@link Outcome}, because a trainer problem must never affect an application (it is not on the start path)
 * and the next scheduled run simply tries again.
 */
public final class TrainingOrchestrator {

    public enum Status {
        /** The store already holds an archive for this fingerprint; nothing was done. */
        ALREADY_STORED,
        /** Trained and uploaded. */
        TRAINED,
        /** Another trainer holds the lock; it will do the work. */
        LOCKED_BY_OTHER,
        /** Something went wrong; see the detail. The next run retries. */
        FAILED
    }

    public record Outcome(Status status, String fingerprint, String detail) {}

    /**
     * @param live     the manifest of this environment (no archive yet)
     * @param training produces the archive file; called at most once, with the lock held
     */
    public Outcome ensure(ArchiveManifest live, ArchiveStore store, TrainingLock lock, Callable<Path> training) {
        String fingerprint = live.fingerprint();
        String repairing = "";
        try {
            ArchiveStore.Inspection found = store.inspect(fingerprint);
            if (found.presence() == ArchiveStore.Presence.USABLE) {
                return new Outcome(Status.ALREADY_STORED, fingerprint, "the store already has a complete entry for this fingerprint");
            }
            if (found.presence() == ArchiveStore.Presence.BROKEN) {
                repairing = " (replacing a broken entry: " + found.detail() + ")";
            }
        } catch (RuntimeException e) {
            return new Outcome(Status.FAILED, fingerprint, "cannot check the store: " + e.getMessage());
        }

        Optional<TrainingLock.Handle> handle;
        try {
            handle = lock.tryAcquire(fingerprint);
        } catch (RuntimeException e) {
            return new Outcome(Status.FAILED, fingerprint, "cannot take the training lock: " + e.getMessage());
        }
        if (handle.isEmpty()) {
            return new Outcome(Status.LOCKED_BY_OTHER, fingerprint, "another trainer holds the lock");
        }
        try (TrainingLock.Handle held = handle.get()) {
            // the previous holder may have finished between our first check and our taking the lock
            if (store.inspect(fingerprint).presence() == ArchiveStore.Presence.USABLE) {
                return new Outcome(Status.ALREADY_STORED, fingerprint, "stored while waiting for the lock");
            }
            Path archive = training.call();
            if (!held.held()) {
                return new Outcome(Status.FAILED, fingerprint, "the training lock was lost during training; not publishing");
            }
            store.put(live.withArchive(archive), archive);
            // The lock is advisory. If it was lost while uploading, a second trainer may publish too; that is
            // harmless (each entry is a digest-verified manifest and archive pair, published by one atomic tag write),
            // but say so.
            String note = held.held() ? "" : " (the lock was lost during the upload; another trainer may also have published)";
            return new Outcome(Status.TRAINED, fingerprint, "archive " + archive.getFileName() + " uploaded" + repairing + note);
        } catch (TrainingException e) {
            return new Outcome(Status.FAILED, fingerprint, "training failed: " + e.getMessage());
        } catch (Exception e) {
            return new Outcome(Status.FAILED, fingerprint, "failed: " + e.getMessage());
        }
    }
}
