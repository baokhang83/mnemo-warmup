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
            List.of(new ArchiveManifest.ClasspathEntry("app.jar", 1, "ab".repeat(32), Instant.EPOCH)), null, "/app");

    private static final class Store implements ArchiveStore {
        boolean present;
        ArchiveManifest published;
        byte[] uploaded;
        Path uploadedPath;
        @Override public boolean exists(String fingerprint) { return present; }
        @Override public void put(ArchiveManifest manifest, Path archive) {
            try { uploaded = Files.readAllBytes(archive); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
            uploadedPath = archive; published = manifest; present = true;
        }
        @Override public Optional<StoredArchive> get(String fingerprint, Path target) { throw new UnsupportedOperationException(); }
    }

    @Test void calculatesMetadataFromTheOutput() throws Exception {
        Path archive = Files.writeString(tmp.resolve("app.jsa"), "trained output");
        LIVE.withArchive(archive).write(tmp.resolve(ArchiveManifest.FILE_NAME));
        Store store = new Store();
        var result = new IsolatedArchivePublisher().publish(LIVE, store, TrainingLock.none(), archive, 100);
        assertEquals(TrainingOrchestrator.Status.TRAINED, result.status());
        assertEquals(LIVE.fingerprint(), store.published.fingerprint());
        assertEquals("trained output", new String(store.uploaded, java.nio.charset.StandardCharsets.UTF_8));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(store.uploadedPath), "private copy is removed after publication");
        assertEquals(Files.size(archive), store.published.archive().sizeBytes());
        assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(archive))), store.published.archive().sha256());
    }

    @Test void refusesMissingOversizedAndSymlinkOutputs() throws Exception {
        Path archive = Files.writeString(tmp.resolve("app.jsa"), "trained output");
        LIVE.withArchive(archive).write(tmp.resolve(ArchiveManifest.FILE_NAME));
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

    @Test void refusesAChangedArchiveOrManifest() throws Exception {
        Path archive = Files.writeString(tmp.resolve("app.jsa"), "trained output");
        LIVE.withArchive(archive).write(tmp.resolve(ArchiveManifest.FILE_NAME));
        Files.writeString(archive, "changed output");
        Store store = new Store();
        assertEquals(TrainingOrchestrator.Status.FAILED,
                new IsolatedArchivePublisher().publish(LIVE, store, TrainingLock.none(), archive, 100).status());
        assertEquals(null, store.published);
        Files.writeString(tmp.resolve(ArchiveManifest.FILE_NAME), "not-json");
        assertEquals(TrainingOrchestrator.Status.FAILED,
                new IsolatedArchivePublisher().publish(LIVE, store, TrainingLock.none(), archive, 100).status());
        assertEquals(null, store.published);
    }

    @Test void existingEntrySkipsTheHandoff() {
        Store store = new Store();
        store.present = true;
        var result = new IsolatedArchivePublisher().publish(LIVE, store, TrainingLock.none(), tmp.resolve("missing"), 100);
        assertEquals(TrainingOrchestrator.Status.ALREADY_STORED, result.status());
        assertEquals(null, store.published);
    }

    @Test void aNamedPipeTimesOutInsteadOfBlockingThePublisher() throws Exception {
        Path fifo = tmp.resolve("app.jsa");
        org.junit.jupiter.api.Assumptions.assumeTrue(new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0, "mkfifo unavailable");
        var publisher = new IsolatedArchivePublisher(200, 1_000);
        long start = System.nanoTime();
        var ex = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> publisher.copyBounded(fifo, java.io.OutputStream.nullOutputStream(), 100, "training output"));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("did not open within the time limit"), ex.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(System.nanoTime() - start < 5_000_000_000L, "returned promptly");
    }

    @Test void boundedCopyRefusesSymlinksAndOversizedFiles() throws Exception {
        var publisher = new IsolatedArchivePublisher(1_000, 1_000);
        Path file = Files.writeString(tmp.resolve("data"), "0123456789");
        var out = new java.io.ByteArrayOutputStream();
        assertEquals(10, publisher.copyBounded(file, out, 10, "x"));
        assertEquals("0123456789", out.toString(java.nio.charset.StandardCharsets.UTF_8));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> publisher.copyBounded(file, java.io.OutputStream.nullOutputStream(), 9, "x"));
        Path link = Files.createSymbolicLink(tmp.resolve("link"), file);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> publisher.copyBounded(link, java.io.OutputStream.nullOutputStream(), 100, "x"));
    }
}
