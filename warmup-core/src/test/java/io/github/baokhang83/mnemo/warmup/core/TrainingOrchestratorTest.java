package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrainingOrchestratorTest {

    @TempDir
    Path tmp;

    private static final ArchiveManifest LIVE = new ArchiveManifest(
            ArchiveManifest.SCHEMA_VERSION,
            CacheStrategy.APP_CDS,
            new ArchiveManifest.Jdk("V", "21.0.5", "21.0.5+11", 21),
            null,
            List.of(),
            List.of(new ArchiveManifest.ClasspathEntry("app.jar", 1, "ab".repeat(32), Instant.EPOCH)),
            null);

    private static final class MemoryStore implements ArchiveStore {
        final Set<String> stored = new HashSet<>();
        final List<String> events = new ArrayList<>();
        RuntimeException existsFailure;
        RuntimeException putFailure;
        Runnable afterFirstExists = () -> {};
        private int existsCalls;

        @Override
        public boolean exists(String fingerprint) {
            if (existsFailure != null) {
                throw existsFailure;
            }
            boolean answer = stored.contains(fingerprint);
            if (existsCalls++ == 0) {
                afterFirstExists.run();
            }
            return answer;
        }

        @Override
        public void put(ArchiveManifest manifest, Path archiveFile) {
            if (putFailure != null) {
                throw putFailure;
            }
            events.add("put");
            stored.add(manifest.fingerprint());
        }

        @Override
        public Optional<StoredArchive> get(String fingerprint, Path targetDir) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingLock implements TrainingLock {
        final List<String> events;
        boolean busy;
        boolean lost;
        RuntimeException failure;

        RecordingLock(List<String> events) {
            this.events = events;
        }

        @Override
        public Optional<Handle> tryAcquire(String key) {
            if (failure != null) {
                throw failure;
            }
            if (busy) {
                return Optional.empty();
            }
            events.add("acquire");
            return Optional.of(new Handle() {
                @Override
                public boolean held() {
                    return !lost;
                }

                @Override
                public void close() {
                    events.add("release");
                }
            });
        }
    }

    private Path archive() throws IOException {
        return Files.writeString(tmp.resolve("app.jsa"), "archive");
    }

    @Test
    void anArchiveThatIsAlreadyStoredMeansNothingIsDone() {
        var store = new MemoryStore();
        store.stored.add(LIVE.fingerprint());
        var lock = new RecordingLock(store.events);
        var trained = new AtomicInteger();

        var outcome = new TrainingOrchestrator().ensure(LIVE, store, lock, () -> {
            trained.incrementAndGet();
            return archive();
        });

        assertEquals(TrainingOrchestrator.Status.ALREADY_STORED, outcome.status());
        assertEquals(0, trained.get());
        assertEquals(List.of(), store.events, "no lock needed");
    }

    @Test
    void trainsUploadsAndReleasesInThatOrder() {
        var store = new MemoryStore();
        var lock = new RecordingLock(store.events);

        var outcome = new TrainingOrchestrator().ensure(LIVE, store, lock, () -> {
            store.events.add("train");
            return archive();
        });

        assertEquals(TrainingOrchestrator.Status.TRAINED, outcome.status(), outcome.detail());
        assertEquals(List.of("acquire", "train", "put", "release"), store.events);
        assertTrue(store.stored.contains(LIVE.fingerprint()));
    }

    @Test
    void anotherTrainerHoldingTheLockMeansWeDoNotTrain() {
        var store = new MemoryStore();
        var lock = new RecordingLock(store.events);
        lock.busy = true;
        var trained = new AtomicInteger();

        var outcome = new TrainingOrchestrator().ensure(LIVE, store, lock, () -> {
            trained.incrementAndGet();
            return archive();
        });

        assertEquals(TrainingOrchestrator.Status.LOCKED_BY_OTHER, outcome.status());
        assertEquals(0, trained.get());
    }

    @Test
    void ifTheArchiveAppearsWhileWeWaitForTheLockWeDoNotTrain() {
        var store = new MemoryStore();
        var lock = new RecordingLock(store.events);
        store.afterFirstExists = () -> store.stored.add(LIVE.fingerprint()); // the other trainer finishes right after our check
        var trained = new AtomicInteger();

        var outcome = new TrainingOrchestrator().ensure(LIVE, store, lock, () -> {
            trained.incrementAndGet();
            return archive();
        });

        assertEquals(TrainingOrchestrator.Status.ALREADY_STORED, outcome.status());
        assertEquals(0, trained.get());
        assertEquals(List.of("acquire", "release"), store.events, "the lock is still released");
    }

    @Test
    void aTrainingFailureIsAnOutcomeThePartialWorkIsNotPublishedAndTheLockIsReleased() {
        var store = new MemoryStore();
        var lock = new RecordingLock(store.events);

        var outcome = new TrainingOrchestrator().ensure(LIVE, store, lock, () -> {
            throw new TrainingException("app crashed", null);
        });

        assertEquals(TrainingOrchestrator.Status.FAILED, outcome.status());
        assertTrue(outcome.detail().contains("app crashed"), outcome.detail());
        assertEquals(List.of("acquire", "release"), store.events);
        assertTrue(store.stored.isEmpty());
    }

    @Test
    void anUploadFailureIsAnOutcomeAndTheLockIsReleased() {
        var store = new MemoryStore();
        store.putFailure = new StoreException("registry refused");
        var lock = new RecordingLock(store.events);

        var outcome = new TrainingOrchestrator().ensure(LIVE, store, lock, this::archive);

        assertEquals(TrainingOrchestrator.Status.FAILED, outcome.status());
        assertTrue(outcome.detail().contains("registry refused"), outcome.detail());
        assertEquals(List.of("acquire", "release"), store.events);
    }

    @Test
    void aLostLockStopsUsFromPublishing() {
        var store = new MemoryStore();
        var lock = new RecordingLock(store.events);

        var outcome = new TrainingOrchestrator().ensure(LIVE, store, lock, () -> {
            lock.lost = true; // the lease was taken over while we trained
            return archive();
        });

        assertEquals(TrainingOrchestrator.Status.FAILED, outcome.status());
        assertTrue(outcome.detail().contains("lock was lost"), outcome.detail());
        assertFalse(store.stored.contains(LIVE.fingerprint()));
    }

    @Test
    void storeAndLockErrorsAreOutcomesNotExceptions() {
        var store = new MemoryStore();
        store.existsFailure = new StoreException("registry down");
        var first = new TrainingOrchestrator().ensure(LIVE, store, TrainingLock.none(), this::archive);
        assertEquals(TrainingOrchestrator.Status.FAILED, first.status());
        assertTrue(first.detail().contains("cannot check the store"), first.detail());

        var store2 = new MemoryStore();
        var lock = new RecordingLock(store2.events);
        lock.failure = new StoreException("HTTP 403 for leases");
        var second = new TrainingOrchestrator().ensure(LIVE, store2, lock, this::archive);
        assertEquals(TrainingOrchestrator.Status.FAILED, second.status());
        assertTrue(second.detail().contains("cannot take the training lock"), second.detail());
    }
}
