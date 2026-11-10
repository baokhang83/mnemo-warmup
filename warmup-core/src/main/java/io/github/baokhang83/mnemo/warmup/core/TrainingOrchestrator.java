package io.github.baokhang83.mnemo.warmup.core;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The trainer's whole decision, in the order ADR 0002 gives: skip if the store already has the archive,
 * otherwise take the lock, check again, train, upload, release. It never throws: every failure is an
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
        try {
            if (store.exists(fingerprint)) {
                return new Outcome(Status.ALREADY_STORED, fingerprint, "the store already has this fingerprint");
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
            if (store.exists(fingerprint)) {
                return new Outcome(Status.ALREADY_STORED, fingerprint, "stored while waiting for the lock");
            }
            Path archive = training.call();
            if (!held.held()) {
                return new Outcome(Status.FAILED, fingerprint, "the training lock was lost during training; not publishing");
            }
            store.put(live.withArchive(archive), archive);
            return new Outcome(Status.TRAINED, fingerprint, "archive " + archive.getFileName() + " uploaded");
        } catch (TrainingException e) {
            return new Outcome(Status.FAILED, fingerprint, "training failed: " + e.getMessage());
        } catch (Exception e) {
            return new Outcome(Status.FAILED, fingerprint, "failed: " + e.getMessage());
        }
    }
}
