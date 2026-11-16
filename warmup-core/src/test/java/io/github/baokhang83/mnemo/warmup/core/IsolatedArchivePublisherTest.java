package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IsolatedArchivePublisherTest {
    @TempDir Path tmp;

    private static final ArchiveManifest LIVE = new ArchiveManifest(
            ArchiveManifest.SCHEMA_VERSION, CacheStrategy.APP_CDS,
            new ArchiveManifest.Jdk("V", "21.0.5", "21.0.5+11", 21), null, List.of(),
            List.of(new ArchiveManifest.ClasspathEntry("app.jar", 1, "ab".repeat(32), Instant.EPOCH)), null);

    private static final class Store implements ArchiveStore {
        boolean present;
        ArchiveManifest published;
        @Override public boolean exists(String fingerprint) { return present; }
        @Override public void put(ArchiveManifest manifest, Path archive) { published = manifest; present = true; }
        @Override public Optional<StoredArchive> get(String fingerprint, Path target) { throw new UnsupportedOperationException(); }
    }

    @Test void calculatesMetadataFromTheOutput() throws Exception {
        Path archive = Files.writeString(tmp.resolve("app.jsa"), "trained output");
        Store store = new Store();
        var result = new IsolatedArchivePublisher().publish(LIVE, store, TrainingLock.none(), archive, 100);
        assertEquals(TrainingOrchestrator.Status.TRAINED, result.status());
        assertEquals(LIVE.fingerprint(), store.published.fingerprint());
        assertEquals(Files.size(archive), store.published.archive().sizeBytes());
        assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(archive))), store.published.archive().sha256());
    }

    @Test void refusesMissingOversizedAndSymlinkOutputs() throws Exception {
        Path archive = Files.writeString(tmp.resolve("app.jsa"), "trained output");
        Path link = tmp.resolve("link.jsa");
        Files.createSymbolicLink(link, archive);
        for (Path candidate : List.of(tmp.resolve("missing"), archive, link)) {
            Store store = new Store();
            var result = new IsolatedArchivePublisher().publish(LIVE, store, TrainingLock.none(), candidate,
                    candidate.equals(archive) ? 1 : 100);
            assertEquals(TrainingOrchestrator.Status.FAILED, result.status(), candidate.toString());
            assertEquals(null, store.published);
        }
    }

    @Test void existingEntrySkipsTheHandoff() {
        Store store = new Store();
        store.present = true;
        var result = new IsolatedArchivePublisher().publish(LIVE, store, TrainingLock.none(), tmp.resolve("missing"), 100);
        assertEquals(TrainingOrchestrator.Status.ALREADY_STORED, result.status());
        assertEquals(null, store.published);
    }
}
