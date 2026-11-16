package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Coordinates one isolated training run. The training callback must run in a different pod. */
public final class IsolatedTrainingCoordinator {
    @FunctionalInterface public interface TrainingStep {
        IsolatedTrainingPodRunner.Outcome run(String handoffName);
    }
    @FunctionalInterface public interface PublisherStep {
        TrainingOrchestrator.Outcome publish(Path handoffDirectory);
    }
    public enum Status { ALREADY_STORED, PUBLISHED, SKIPPED, FAILED }
    /** A retained handoff needs operator cleanup after the training pod is confirmed stopped. */
    public record Outcome(Status status, String detail, String retainedHandoff) {}

    /**
     * The check is an optimization; the publisher must recheck under its own lease. On any ambiguous
     * training result the handoff is retained, because the training pod may still be writing it.
     */
    public Outcome run(ArchiveManifest live, ArchiveStore store, Path claimRoot,
                       TrainingStep training, PublisherStep publisher) {
        Objects.requireNonNull(live);
        Objects.requireNonNull(store);
        Objects.requireNonNull(training);
        Objects.requireNonNull(publisher);
        try {
            if (store.inspect(live.fingerprint()).presence() == ArchiveStore.Presence.USABLE) {
                return new Outcome(Status.ALREADY_STORED, "A usable archive already exists", null);
            }
        } catch (RuntimeException e) {
            return new Outcome(Status.FAILED, "Cannot check the archive store", null);
        }

        final IsolatedHandoff handoff;
        try {
            handoff = IsolatedHandoff.create(claimRoot);
        } catch (IOException | RuntimeException e) {
            return new Outcome(Status.FAILED, "Cannot create a handoff directory", null);
        }
        final IsolatedTrainingPodRunner.Outcome trained;
        try {
            trained = training.run(handoff.name());
        } catch (RuntimeException e) {
            // A request can fail after the API server created the pod. Do not remove its volume.
            return new Outcome(Status.FAILED, "Training state is unknown; verify the pod stopped before cleanup", handoff.name());
        }
        if (trained == null || trained.status() != IsolatedTrainingPodRunner.Status.SUCCEEDED) {
            return new Outcome(Status.FAILED, "Training did not complete successfully; verify the pod stopped before cleanup", handoff.name());
        }

        Outcome result;
        try {
            TrainingOrchestrator.Outcome published = publisher.publish(handoff.directory());
            if (published == null || published.status() == TrainingOrchestrator.Status.FAILED) {
                result = new Outcome(Status.FAILED, "Archive publication failed", null);
            } else if (published.status() == TrainingOrchestrator.Status.TRAINED) {
                result = new Outcome(Status.PUBLISHED, "Archive published", null);
            } else {
                result = new Outcome(Status.SKIPPED, "Publication was skipped by the store or lease check", null);
            }
        } catch (RuntimeException e) {
            result = new Outcome(Status.FAILED, "Archive publication failed", null);
        }
        try {
            handoff.close();
        } catch (IOException | RuntimeException e) {
            return new Outcome(result.status(), result.detail() + "; handoff cleanup failed", handoff.name());
        }
        if (trained.cleanupRequestFailed()) {
            return new Outcome(result.status(), result.detail() + "; training pod cleanup request failed", null);
        }
        return result;
    }
}
