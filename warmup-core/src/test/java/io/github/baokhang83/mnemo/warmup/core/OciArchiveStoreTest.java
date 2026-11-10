package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OciArchiveStoreTest {

    private static final String HEX = "ab".repeat(32);

    @TempDir
    Path tmp;

    private ArchiveManifest manifestFor(Path archive) {
        var manifest = new ArchiveManifest(
                ArchiveManifest.SCHEMA_VERSION,
                CacheStrategy.APP_CDS,
                new ArchiveManifest.Jdk("Eclipse Adoptium", "21.0.5", "21.0.5+11", 21),
                "sha256:" + HEX,
                List.of("-Xmx512m"),
                List.of(new ArchiveManifest.ClasspathEntry("app.jar", 10, HEX, java.time.Instant.EPOCH)),
                null);
        return manifest.withArchive(archive);
    }

    private Path archive(String content) throws IOException {
        Path file = tmp.resolve("app.jsa");
        Files.writeString(file, content);
        return file;
    }

    private OciArchiveStore store(FakeRegistry registry, RegistryCredentials credentials) {
        return OciArchiveStore.of(registry.reference("test/archives"), true, credentials, Duration.ofSeconds(10));
    }

    @Test
    void tagEncodingRoundTripsAndRejectsOtherShapes() {
        String fingerprint = "sha256:" + HEX;
        assertEquals("fp-" + HEX, OciArchiveStore.tagFor(fingerprint));
        assertEquals(fingerprint, OciArchiveStore.fingerprintOf("fp-" + HEX));
        assertThrows(IllegalArgumentException.class, () -> OciArchiveStore.tagFor("sha256:short"));
        assertThrows(IllegalArgumentException.class, () -> OciArchiveStore.tagFor(HEX));
        assertThrows(IllegalArgumentException.class, () -> OciArchiveStore.fingerprintOf("latest"));
    }

    @Test
    void rejectsMalformedReferences() {
        assertThrows(IllegalArgumentException.class, () -> OciArchiveStore.registryHost("registry.example.com/x"));
        assertThrows(IllegalArgumentException.class, () -> OciArchiveStore.registryHost("oci://registry.example.com"));
        assertThrows(IllegalArgumentException.class, () -> OciArchiveStore.registryHost("oci://host/Upper"));
        assertEquals("registry.example.com:5000", OciArchiveStore.registryHost("oci://registry.example.com:5000/team/warmup"));
    }

    @Test
    void putThenExistsThenGetReturnsTheSameBytesAndManifest() throws Exception {
        try (var registry = new FakeRegistry()) {
            Path file = archive("archive-bytes-" + "x".repeat(10_000));
            ArchiveManifest manifest = manifestFor(file);
            var store = store(registry, null);

            assertFalse(store.exists(manifest.fingerprint()));
            store.put(manifest, file);
            assertTrue(store.exists(manifest.fingerprint()));

            Path target = Files.createDirectory(tmp.resolve("out"));
            Optional<ArchiveStore.StoredArchive> got = store.get(manifest.fingerprint(), target);
            assertTrue(got.isPresent());
            assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(got.get().archiveFile()));
            assertEquals(manifest.fingerprint(), got.get().manifest().fingerprint());
            assertEquals(target.resolve("app.jsa"), got.get().archiveFile());
        }
    }

    @Test
    void getOfAMissingFingerprintIsEmpty() throws Exception {
        try (var registry = new FakeRegistry()) {
            assertTrue(store(registry, null).get("sha256:" + HEX, tmp).isEmpty());
        }
    }

    @Test
    void putIsRepeatableWithoutReuploadingBlobs() throws Exception {
        try (var registry = new FakeRegistry()) {
            Path file = archive("same");
            ArchiveManifest manifest = manifestFor(file);
            var store = store(registry, null);
            store.put(manifest, file);
            store.put(manifest, file);
            assertEquals(2, registry.blobs.size());
            assertEquals(1, registry.manifests.size());
        }
    }

    @Test
    void followsBlobRedirects() throws Exception {
        try (var registry = new FakeRegistry()) {
            registry.redirectBlobs = true;
            Path file = archive("redirected");
            ArchiveManifest manifest = manifestFor(file);
            var store = store(registry, null);
            store.put(manifest, file);
            Path out = Files.createDirectory(tmp.resolve("out"));
            assertEquals(
                    "redirected",
                    Files.readString(store.get(manifest.fingerprint(), out).orElseThrow().archiveFile()));
        }
    }

    @Test
    void bearerAuthenticationUsesTheCredentials() throws Exception {
        try (var registry = new FakeRegistry()) {
            registry.requireAuth = true;
            Path file = archive("secured");
            ArchiveManifest manifest = manifestFor(file);
            var store = store(registry, new RegistryCredentials(FakeRegistry.USER, FakeRegistry.PASSWORD));
            store.put(manifest, file);
            assertTrue(store.exists(manifest.fingerprint()));
            assertTrue(registry.tokenRequests.get() >= 1);
        }
    }

    @Test
    void missingOrWrongCredentialsFailWithAStoreException() throws Exception {
        try (var registry = new FakeRegistry()) {
            registry.requireAuth = true;
            var anonymous = store(registry, null);
            var ex = assertThrows(StoreException.class, () -> anonymous.exists("sha256:" + HEX));
            assertTrue(ex.getMessage().contains("credentials") || ex.getMessage().contains("refused"), ex.getMessage());

            var wrong = store(registry, new RegistryCredentials("robot", "nope"));
            assertThrows(StoreException.class, () -> wrong.exists("sha256:" + HEX));
        }
    }

    @Test
    void aCorruptedBlobIsRejectedAndLeavesNoFile() throws Exception {
        try (var registry = new FakeRegistry()) {
            Path file = archive("original-content");
            ArchiveManifest manifest = manifestFor(file);
            var store = store(registry, null);
            store.put(manifest, file);
            String archiveDigest = "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            registry.blobs.put(archiveDigest, "tampered-content".getBytes(StandardCharsets.UTF_8));

            Path out = Files.createDirectory(tmp.resolve("out"));
            assertThrows(StoreException.class, () -> store.get(manifest.fingerprint(), out));
            try (var files = Files.list(out)) {
                assertEquals(0, files.count(), "no partial or tampered file may remain");
            }
        }
    }

    @Test
    void unreachableRegistryIsAStoreException() {
        var store = OciArchiveStore.of("oci://127.0.0.1:1/test/archives", true, null, Duration.ofSeconds(2));
        assertThrows(StoreException.class, () -> store.exists("sha256:" + HEX));
    }

    @Test
    void readsDockerConfigCredentialsByHost() throws Exception {
        String auth = Base64.getEncoder().encodeToString("robot:p:ass".getBytes(StandardCharsets.UTF_8));
        Path config = tmp.resolve("config.json");
        Files.writeString(config, "{\"auths\":{\"https://registry.example.com/v1/\":{\"auth\":\"" + auth + "\"},"
                + "\"other.io\":{\"username\":\"u\",\"password\":\"p\"}}}");

        var creds = RegistryCredentials.fromDockerConfig(config, "registry.example.com").orElseThrow();
        assertEquals("robot", creds.username());
        assertEquals("p:ass", creds.password());
        assertEquals("u", RegistryCredentials.fromDockerConfig(config, "other.io").orElseThrow().username());
        assertTrue(RegistryCredentials.fromDockerConfig(config, "unknown.io").isEmpty());
        assertFalse(creds.toString().contains("p:ass"));
    }

    @Test
    void badCredentialFilesAreStoreExceptions() throws Exception {
        assertThrows(StoreException.class, () -> RegistryCredentials.fromDockerConfig(tmp.resolve("missing.json"), "h"));
        Path bad = tmp.resolve("bad.json");
        Files.writeString(bad, "not json");
        assertThrows(StoreException.class, () -> RegistryCredentials.fromDockerConfig(bad, "h"));
    }

    @Test
    void relativeAndQueryOnlyUploadLocationsAreResolvedAgainstTheUploadUrl() throws Exception {
        for (String style : List.of("relative", "query")) {
            try (var registry = new FakeRegistry()) {
                registry.locationStyle = style;
                Path file = archive("location-" + style);
                ArchiveManifest manifest = manifestFor(file);
                var store = store(registry, null);
                store.put(manifest, file);
                assertTrue(store.exists(manifest.fingerprint()), style);
                assertEquals(2, registry.blobs.size(), style);
            }
        }
    }

    @Test
    void aStalledDownloadFailsWithinTheTimeoutAndLeavesNoFile() throws Exception {
        try (var registry = new FakeRegistry()) {
            Path file = archive("s".repeat(50_000));
            ArchiveManifest manifest = manifestFor(file);
            OciArchiveStore.of(registry.reference("test/archives"), true, null, Duration.ofSeconds(5)).put(manifest, file);
            registry.stallBlobs = true;

            var shortTimeout = OciArchiveStore.of(registry.reference("test/archives"), true, null, Duration.ofSeconds(1));
            Path out = Files.createDirectory(tmp.resolve("out"));
            long started = System.nanoTime();
            var ex = assertThrows(StoreException.class, () -> shortTimeout.get(manifest.fingerprint(), out));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 6, "must not wait for the server");
            assertTrue(ex.getMessage().contains("stalled") || ex.getMessage().contains("Cannot"), ex.getMessage());
            try (var files = Files.list(out)) {
                assertEquals(0, files.count());
            }
        }
    }

    @Test
    void httpsRegistriesNeverFollowAnythingToPlainHttp() {
        var https = java.net.URI.create("https://registry.example");
        assertThrows(StoreException.class, () -> OciArchiveStore.checkTransport(https, java.net.URI.create("http://registry.example/v2/")));
        OciArchiveStore.checkTransport(https, java.net.URI.create("https://storage.example/blob"));
        OciArchiveStore.checkTransport(java.net.URI.create("http://127.0.0.1:5000"), java.net.URI.create("http://127.0.0.1:5000/x"));
        assertFalse(OciArchiveStore.sameOrigin(https, java.net.URI.create("http://registry.example")));
        assertFalse(OciArchiveStore.sameOrigin(https, java.net.URI.create("https://registry.example:8443")));
        assertTrue(OciArchiveStore.sameOrigin(https, java.net.URI.create("https://REGISTRY.example/other")));
    }

    @Test
    void putRejectsAnArchiveThatDoesNotMatchItsManifest() throws Exception {
        try (var registry = new FakeRegistry()) {
            Path file = archive("what-the-manifest-describes");
            ArchiveManifest manifest = manifestFor(file);
            Files.writeString(file, "replaced-after-build");
            var store = store(registry, null);
            assertThrows(IllegalArgumentException.class, () -> store.put(manifest, file));
            assertEquals(0, registry.blobs.size());
            assertEquals(0, registry.manifests.size());
        }
    }

    // ---- inspect: a tag that exists is not necessarily a usable entry ----

    private static String digestOf(byte[] bytes) throws Exception {
        return "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void inspectDistinguishesAbsentUsableAndBrokenEntries() throws Exception {
        try (var registry = new FakeRegistry()) {
            Path file = archive("inspect-me");
            ArchiveManifest manifest = manifestFor(file);
            var store = store(registry, null);
            assertEquals(ArchiveStore.Presence.ABSENT, store.inspect(manifest.fingerprint()).presence());

            store.put(manifest, file);
            assertEquals(ArchiveStore.Presence.USABLE, store.inspect(manifest.fingerprint()).presence());

            registry.blobs.remove(digestOf(Files.readAllBytes(file))); // the archive blob vanishes (a partial push or GC)
            var broken = store.inspect(manifest.fingerprint());
            assertEquals(ArchiveStore.Presence.BROKEN, broken.presence());
            assertTrue(broken.detail().contains("archive blob") && broken.detail().contains("missing"), broken.detail());
        }
    }

    @Test
    void inspectCatchesAWrongSizedArchiveBlobAndADamagedOrMisfiledManifest() throws Exception {
        try (var registry = new FakeRegistry()) {
            Path file = archive("size-matters");
            ArchiveManifest manifest = manifestFor(file);
            var store = store(registry, null);
            store.put(manifest, file);
            String layer = digestOf(Files.readAllBytes(file));

            registry.blobs.put(layer, "short".getBytes(StandardCharsets.UTF_8)); // a different length under the same digest
            assertTrue(store.inspect(manifest.fingerprint()).detail().contains("declares"), store.inspect(manifest.fingerprint()).detail());
            registry.blobs.put(layer, Files.readAllBytes(file));
            assertEquals(ArchiveStore.Presence.USABLE, store.inspect(manifest.fingerprint()).presence());

            String configDigest = registry.blobs.keySet().stream().filter(d -> !d.equals(layer)).findFirst().orElseThrow();
            byte[] config = registry.blobs.get(configDigest);
            registry.blobs.put(configDigest, "{}".getBytes(StandardCharsets.UTF_8));
            assertTrue(store.inspect(manifest.fingerprint()).detail().contains("does not match its digest"), store.inspect(manifest.fingerprint()).detail());
            registry.blobs.put(configDigest, config);

            // an entry filed under a tag that belongs to another fingerprint
            String otherFingerprint = "sha256:" + "cd".repeat(32);
            registry.manifests.put(OciArchiveStore.tagFor(otherFingerprint), registry.manifests.get(OciArchiveStore.tagFor(manifest.fingerprint())));
            assertTrue(store.inspect(otherFingerprint).detail().contains("another fingerprint"), store.inspect(otherFingerprint).detail());

            registry.manifests.put(OciArchiveStore.tagFor(manifest.fingerprint()), "not json".getBytes(StandardCharsets.UTF_8));
            assertEquals(ArchiveStore.Presence.BROKEN, store.inspect(manifest.fingerprint()).presence());
        }
    }

    @Test
    void inspectOfAnUnreachableStoreIsAStoreExceptionNotAVerdict() {
        var store = OciArchiveStore.of("oci://127.0.0.1:1/test/archives", true, null, Duration.ofSeconds(2));
        assertThrows(StoreException.class, () -> store.inspect("sha256:" + HEX));
    }

    @Test
    void putReplacesABrokenEntryUnderTheSameTag() throws Exception {
        try (var registry = new FakeRegistry()) {
            Path file = archive("repair-me");
            ArchiveManifest manifest = manifestFor(file);
            var store = store(registry, null);
            store.put(manifest, file);
            registry.blobs.remove(digestOf(Files.readAllBytes(file)));
            assertEquals(ArchiveStore.Presence.BROKEN, store.inspect(manifest.fingerprint()).presence());

            store.put(manifest, file); // the trainer's repair: re-uploads the missing blob, rewrites the tag

            assertEquals(ArchiveStore.Presence.USABLE, store.inspect(manifest.fingerprint()).presence());
            Path out = Files.createDirectory(tmp.resolve("repaired"));
            assertEquals("repair-me", Files.readString(store.get(manifest.fingerprint(), out).orElseThrow().archiveFile()));
        }
    }
}
