package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IsolatedTrainingCoordinatorTest {
    @TempDir Path tmp;
    private static final ArchiveManifest LIVE = new ArchiveManifest(ArchiveManifest.SCHEMA_VERSION, CacheStrategy.APP_CDS,
            new ArchiveManifest.Jdk("V", "21.0.5", "21.0.5+11", 21), null, List.of(),
            List.of(new ArchiveManifest.ClasspathEntry("app.jar", 1, "ab".repeat(32), Instant.EPOCH)), null);
    private static final class Store implements ArchiveStore {
        boolean present;
        @Override public boolean exists(String fingerprint) { return present; }
        @Override public void put(ArchiveManifest manifest, Path file) { present = true; }
        @Override public Optional<StoredArchive> get(String fingerprint, Path target) { throw new UnsupportedOperationException(); }
    }
    @Test void checksTrainsPublishesAndCleansTheUniqueHandoff() {
        Store store = new Store();
        var result = new IsolatedTrainingCoordinator().run(LIVE, store, tmp, name -> {
            try { Files.writeString(tmp.resolve(name).resolve("app.jsa"), "trained"); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
            return new IsolatedTrainingPodRunner.Outcome(IsolatedTrainingPodRunner.Status.SUCCEEDED, "done", false);
        }, dir -> new IsolatedArchivePublisher().publish(LIVE, store, TrainingLock.none(), dir.resolve("app.jsa"), 100));
        assertEquals(IsolatedTrainingCoordinator.Status.PUBLISHED, result.status());
        assertTrue(store.present);
        assertEquals(null, result.retainedHandoff());
        try (var entries = Files.list(tmp)) { assertEquals(0, entries.count()); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
    }
    @Test void aUsableEntrySkipsTrainingBeforeAllocatingStorage() {
        Store store = new Store(); store.present = true;
        AtomicInteger calls = new AtomicInteger();
        var result = new IsolatedTrainingCoordinator().run(LIVE, store, tmp, name -> { calls.incrementAndGet(); return null; },
                dir -> { calls.incrementAndGet(); return null; });
        assertEquals(IsolatedTrainingCoordinator.Status.ALREADY_STORED, result.status());
        assertEquals(0, calls.get());
    }
    @Test void ambiguousOrFailedTrainingRetainsTheHandoffAndNeverPublishes() {
        for (var status : List.of(IsolatedTrainingPodRunner.Status.API_ERROR, IsolatedTrainingPodRunner.Status.FAILED,
                IsolatedTrainingPodRunner.Status.TIMED_OUT)) {
            AtomicInteger calls = new AtomicInteger();
            var result = new IsolatedTrainingCoordinator().run(LIVE, new Store(), tmp,
                    name -> new IsolatedTrainingPodRunner.Outcome(status, "failure", false),
                    dir -> { calls.incrementAndGet(); return null; });
            assertEquals(IsolatedTrainingCoordinator.Status.FAILED, result.status());
            assertNotNull(result.retainedHandoff());
            assertTrue(Files.isDirectory(tmp.resolve(result.retainedHandoff())));
            assertEquals(0, calls.get());
        }
    }
    @Test void publicationFailureCleansUpOnceTrainingHasStopped() {
        var result = new IsolatedTrainingCoordinator().run(LIVE, new Store(), tmp,
                name -> new IsolatedTrainingPodRunner.Outcome(IsolatedTrainingPodRunner.Status.SUCCEEDED, "done", false),
                dir -> { throw new IllegalStateException("failed"); });
        assertEquals(IsolatedTrainingCoordinator.Status.FAILED, result.status());
        assertEquals(null, result.retainedHandoff());
        assertFalse(result.detail().contains("cleanup failed"));
    }
}
