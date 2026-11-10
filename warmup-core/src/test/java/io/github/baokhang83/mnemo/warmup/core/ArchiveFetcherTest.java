package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveFetcherTest {

    @TempDir
    Path tmp;

    private static ArchiveManifest live(CacheStrategy type) {
        return new ArchiveManifest(
                ArchiveManifest.SCHEMA_VERSION,
                type,
                new ArchiveManifest.Jdk("Eclipse Adoptium", "25.0.1", "25.0.1+8", 25),
                null,
                List.of("-Xmx512m"),
                List.of(new ArchiveManifest.ClasspathEntry("app.jar", 10, "ab".repeat(32), Instant.EPOCH)),
                null);
    }

    private ArchiveManifest trained(CacheStrategy type, String content) throws IOException {
        Path file = Files.writeString(tmp.resolve("trained-" + type + ".bin"), content);
        return live(type).withArchive(file);
    }

    /** An in-memory store; fingerprints map to a manifest and the archive bytes. */
    private static final class MemoryStore implements ArchiveStore {
        final Map<String, ArchiveManifest> manifests = new HashMap<>();
        final Map<String, String> contents = new HashMap<>();
        final List<String> asked = new java.util.ArrayList<>();
        RuntimeException failure;

        @Override
        public boolean exists(String fingerprint) {
            return manifests.containsKey(fingerprint);
        }

        @Override
        public void put(ArchiveManifest manifest, Path archiveFile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredArchive> get(String fingerprint, Path targetDir) {
            asked.add(fingerprint);
            if (failure != null) {
                throw failure;
            }
            ArchiveManifest m = manifests.get(fingerprint);
            if (m == null) {
                return Optional.empty();
            }
            try {
                Path file = targetDir.resolve("app.bin");
                Files.writeString(file, contents.get(fingerprint));
                return Optional.of(new StoredArchive(m, file));
            } catch (IOException e) {
                throw new StoreException(e.getMessage(), e);
            }
        }
    }

    @Test
    void aHitWritesArchiveManifestAndAFlagsFileWithTheRuntimeFlag() throws Exception {
        var store = new MemoryStore();
        ArchiveManifest appCds = trained(CacheStrategy.APP_CDS, "cds");
        store.manifests.put(appCds.fingerprint(), appCds);
        store.contents.put(appCds.fingerprint(), "cds");
        Path out = tmp.resolve("out");

        var result = new ArchiveFetcher().fetch(store, List.of(live(CacheStrategy.APP_CDS)), out);

        assertEquals(ArchiveFetcher.Outcome.HIT, result.outcome());
        assertEquals(CacheStrategy.APP_CDS, result.cacheType());
        String flags = Files.readString(out.resolve(ArchiveFetcher.FLAGS_FILE));
        assertEquals("-XX:SharedArchiveFile=" + out.resolve("app.bin").toAbsolutePath() + "\n", flags);
        assertTrue(Files.isRegularFile(out.resolve(ArchiveManifest.FILE_NAME)));
        assertEquals(appCds.fingerprint(), ArchiveManifest.read(out.resolve(ArchiveManifest.FILE_NAME)).fingerprint());
    }

    @Test
    void prefersTheFirstCandidateAndDoesNotAskForTheRest() throws Exception {
        var store = new MemoryStore();
        for (CacheStrategy type : CacheStrategy.values()) {
            ArchiveManifest m = trained(type, type.name());
            store.manifests.put(m.fingerprint(), m);
            store.contents.put(m.fingerprint(), type.name());
        }
        var result = new ArchiveFetcher()
                .fetch(store, List.of(live(CacheStrategy.LEYDEN_AOT), live(CacheStrategy.APP_CDS)), tmp.resolve("out"));

        assertEquals(CacheStrategy.LEYDEN_AOT, result.cacheType());
        assertEquals(1, store.asked.size());
        assertTrue(Files.readString(result.flagsFile()).startsWith("-XX:AOTCache="));
    }

    @Test
    void fallsBackFromAotToAppCdsWhenOnlyAppCdsIsStored() throws Exception {
        var store = new MemoryStore();
        ArchiveManifest appCds = trained(CacheStrategy.APP_CDS, "cds");
        store.manifests.put(appCds.fingerprint(), appCds);
        store.contents.put(appCds.fingerprint(), "cds");

        var result = new ArchiveFetcher()
                .fetch(store, List.of(live(CacheStrategy.LEYDEN_AOT), live(CacheStrategy.APP_CDS)), tmp.resolve("out"));

        assertEquals(ArchiveFetcher.Outcome.HIT, result.outcome());
        assertEquals(CacheStrategy.APP_CDS, result.cacheType());
        assertEquals(1, result.notes().size());
        assertTrue(result.notes().get(0).startsWith("LEYDEN_AOT"), result.notes().toString());
    }

    @Test
    void aMissLeavesNoFlagsAndRemovesStaleOnes() throws Exception {
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(out.resolve(ArchiveFetcher.FLAGS_FILE), "-XX:SharedArchiveFile=/old/archive.jsa\n");
        Files.writeString(out.resolve(ArchiveManifest.FILE_NAME), "{}");

        var result = new ArchiveFetcher().fetch(new MemoryStore(), List.of(live(CacheStrategy.APP_CDS)), out);

        assertEquals(ArchiveFetcher.Outcome.MISS, result.outcome());
        assertNull(result.flagsFile());
        assertFalse(Files.exists(out.resolve(ArchiveFetcher.FLAGS_FILE)));
        assertFalse(Files.exists(out.resolve(ArchiveManifest.FILE_NAME)));
        assertEquals(1, result.notes().size());
    }

    @Test
    void aStoreFailureIsANoteNotAnException() {
        var store = new MemoryStore();
        store.failure = new StoreException("registry unreachable");
        var result = new ArchiveFetcher()
                .fetch(store, List.of(live(CacheStrategy.LEYDEN_AOT), live(CacheStrategy.APP_CDS)), tmp.resolve("out"));

        assertEquals(ArchiveFetcher.Outcome.MISS, result.outcome());
        assertEquals(2, result.notes().size());
        assertTrue(result.notes().get(0).contains("registry unreachable"));
    }

    @Test
    void aStoredManifestThatDoesNotMatchTheLiveEnvironmentIsRejected() throws Exception {
        var store = new MemoryStore();
        ArchiveManifest wanted = live(CacheStrategy.APP_CDS);
        // stored under the live fingerprint but trained with different JVM options
        ArchiveManifest other = new ArchiveManifest(
                        wanted.schemaVersion(), wanted.cacheType(), wanted.jdk(), null, List.of("-Xmx1g"), wanted.classpath(), null)
                .withArchive(Files.writeString(tmp.resolve("x.bin"), "x"));
        store.manifests.put(wanted.fingerprint(), other);
        store.contents.put(wanted.fingerprint(), "x");
        Path out = tmp.resolve("out");

        var result = new ArchiveFetcher().fetch(store, List.of(wanted), out);

        assertEquals(ArchiveFetcher.Outcome.MISS, result.outcome());
        assertFalse(Files.exists(out.resolve(ArchiveFetcher.FLAGS_FILE)));
        assertFalse(Files.exists(out.resolve("app.bin")), "a rejected archive must not stay on disk");
    }

    @Test
    void aTamperedArchiveInARealStoreIsAMissWithAReason() throws Exception {
        try (var registry = new FakeRegistry()) {
            Path file = Files.writeString(tmp.resolve("app.jsa"), "original-content");
            ArchiveManifest manifest = live(CacheStrategy.APP_CDS).withArchive(file);
            var oci = OciArchiveStore.of(registry.reference("test/archives"), true, null, Duration.ofSeconds(5));
            oci.put(manifest, file);
            String digest = "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            registry.blobs.put(digest, "tampered-content".getBytes(StandardCharsets.UTF_8));
            Path out = tmp.resolve("out");

            var result = new ArchiveFetcher().fetch(oci, List.of(live(CacheStrategy.APP_CDS)), out);

            assertEquals(ArchiveFetcher.Outcome.MISS, result.outcome());
            assertTrue(result.notes().get(0).contains("verification"), result.notes().toString());
            assertFalse(Files.exists(out.resolve(ArchiveFetcher.FLAGS_FILE)));
            try (var files = Files.list(out)) {
                assertEquals(0, files.count());
            }
        }
    }

    /** A store that writes the archive and then makes the output directory read-only, as a full or broken volume would. */
    private static final class LockingStore implements ArchiveStore {
        final ArchiveManifest manifest;

        LockingStore(ArchiveManifest manifest) {
            this.manifest = manifest;
        }

        @Override
        public boolean exists(String fingerprint) {
            return true;
        }

        @Override
        public void put(ArchiveManifest m, Path archiveFile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredArchive> get(String fingerprint, Path targetDir) {
            try {
                Path file = Files.writeString(targetDir.resolve("app.bin"), "x");
                targetDir.toFile().setWritable(false);
                return Optional.of(new StoredArchive(manifest, file));
            } catch (IOException e) {
                throw new StoreException(e.getMessage(), e);
            }
        }
    }

    @Test
    void ifPublishingFailsAfterTheDownloadNoFlagsFileIsLeftBehind() throws Exception {
        ArchiveManifest wanted = live(CacheStrategy.APP_CDS);
        ArchiveManifest stored = wanted.withArchive(Files.writeString(tmp.resolve("x.bin"), "x"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        try {
            var thrown = assertThrows(
                    StoreException.class, () -> new ArchiveFetcher().fetch(new LockingStore(stored), List.of(wanted), out));
            assumeTrue(!Files.isWritable(out), "needs a non-root user so a read-only directory rejects writes");
            assertTrue(thrown.getMessage().contains("Cannot write"), thrown.getMessage());
            assertFalse(Files.exists(out.resolve(ArchiveFetcher.FLAGS_FILE)), "flags are the last thing written");
        } finally {
            out.toFile().setWritable(true);
        }
    }

    @Test
    void staleFlagsThatCannotBeDeletedAreEmptiedSoTheyMeanRunPlain() throws Exception {
        Path out = Files.createDirectories(tmp.resolve("out"));
        Path flags = Files.writeString(out.resolve(ArchiveFetcher.FLAGS_FILE), "-XX:SharedArchiveFile=/stale.jsa\n");
        try {
            out.toFile().setWritable(false);
            assumeTrue(!Files.isWritable(out), "needs a non-root user so a read-only directory rejects deletes");
            ArchiveFetcher.clear(out);
            // the manifest delete is a no-op here (absent); the flags must no longer name an archive
            assertEquals("", Files.readString(flags));
        } finally {
            out.toFile().setWritable(true);
        }
    }

    @Test
    void ifStaleFlagsCanNeitherBeDeletedNorEmptiedTheFailureIsReported() throws Exception {
        Path out = Files.createDirectories(tmp.resolve("out"));
        Path flags = Files.writeString(out.resolve(ArchiveFetcher.FLAGS_FILE), "-XX:SharedArchiveFile=/stale.jsa\n");
        try {
            out.toFile().setWritable(false);
            flags.toFile().setWritable(false);
            assumeTrue(!Files.isWritable(flags), "needs a non-root user");
            var ex = assertThrows(StoreException.class, () -> ArchiveFetcher.clear(out));
            assertTrue(ex.getMessage().contains("old archive may still be referenced"), ex.getMessage());
        } finally {
            out.toFile().setWritable(true);
            flags.toFile().setWritable(true);
        }
    }
}
