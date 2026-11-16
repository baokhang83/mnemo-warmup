package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Signed archives (docs/adr/0013): what is published, and what a pod or the trainer will accept. */
class OciArchiveStoreSigningTest {

    private static final String HEX = "ab".repeat(32);

    @TempDir
    Path tmp;

    private ArchiveManifest manifestFor(Path archive) {
        return new ArchiveManifest(ArchiveManifest.SCHEMA_VERSION, CacheStrategy.APP_CDS,
                new ArchiveManifest.Jdk("Eclipse Adoptium", "21.0.5", "21.0.5+11", 21), "sha256:" + HEX, List.of("-Xmx512m"),
                List.of(new ArchiveManifest.ClasspathEntry("app.jar", 10, HEX, java.time.Instant.EPOCH)), null).withArchive(archive);
    }

    private Path archive(String content) throws IOException {
        Path file = tmp.resolve("app.jsa");
        Files.writeString(file, content);
        return file;
    }

    private OciArchiveStore store(FakeRegistry registry, String repository) {
        return OciArchiveStore.of(registry.reference(repository), true, null, Duration.ofSeconds(10));
    }

    private static ArchiveVerifier trusting(SigningKey... keys) {
        StringBuilder entries = new StringBuilder();
        for (SigningKey k : keys) {
            entries.append(entries.length() == 0 ? "" : ",").append(k.publicKeyEntry());
        }
        return ArchiveVerifier.fromEntries(entries.toString(), n -> { });
    }

    private static String tagOf(ArchiveManifest m) {
        return OciArchiveStore.tagFor(m.fingerprint());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> imageOf(FakeRegistry registry, String tag) {
        return (Map<String, Object>) Json.parse(new String(registry.manifests.get(tag), StandardCharsets.UTF_8));
    }

    private static void storeImage(FakeRegistry registry, String tag, Map<String, Object> image) {
        registry.manifests.put(tag, Json.writeCompact(image).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aSignedArchiveIsFetchedWithItsSignatureAndTheManifestBytesUnchanged() throws Exception {
        try (var registry = new FakeRegistry()) {
            SigningKey key = SigningKey.generate();
            Path file = archive("s".repeat(5_000));
            ArchiveManifest manifest = manifestFor(file);
            store(registry, "test/archives").withSigningKey(key).put(manifest, file);
            var annotations = (Map<?, ?>) imageOf(registry, tagOf(manifest)).get("annotations");
            assertEquals(key.keyId(), annotations.get(OciArchiveStore.ANNOTATION_KEY_ID));

            var hit = store(registry, "test/archives").withVerifier(trusting(key)).get(manifest.fingerprint(), tmp.resolve("o1")).orElseThrow();
            assertNotNull(hit.signature());
            assertEquals(key.keyId(), hit.signature().keyId());
            assertEquals("test/archives", hit.repository());
            // the manifest file is the registry's config blob byte for byte (a signature covers those bytes)
            byte[] configBlob = registry.blobs.values().stream().filter(b -> b.length > 0 && b[0] == '{').findFirst().orElseThrow();
            assertArrayEquals(configBlob, hit.manifestBytes());
        }
    }

    @Test
    void withoutAVerifierSignedAndUnsignedArchivesAreBothAccepted() throws Exception {
        try (var registry = new FakeRegistry()) {
            Path file = archive("s".repeat(5_000));
            ArchiveManifest manifest = manifestFor(file);
            store(registry, "test/archives").put(manifest, file); // unsigned
            assertTrue(store(registry, "test/archives").get(manifest.fingerprint(), tmp.resolve("o2")).isPresent());
            store(registry, "test/archives").withSigningKey(SigningKey.generate()).put(manifest, file); // signed
            assertTrue(store(registry, "test/archives").get(manifest.fingerprint(), tmp.resolve("o3")).isPresent());
        }
    }

    @Test
    void anUnsignedArchiveIsRefusedWhenKeysAreConfigured() throws Exception {
        try (var registry = new FakeRegistry()) {
            Path file = archive("s".repeat(5_000));
            ArchiveManifest manifest = manifestFor(file);
            store(registry, "test/archives").put(manifest, file);
            var pod = store(registry, "test/archives").withVerifier(trusting(SigningKey.generate()));
            int before = registry.blobGets.get();
            var ex = assertThrows(StoreException.class, () -> pod.get(manifest.fingerprint(), tmp.resolve("o4")));
            assertTrue(ex.getMessage().contains("not signed") && ex.getMessage().contains("not using it"), ex.getMessage());
            assertFalse(Files.exists(tmp.resolve("o4").resolve("app.jsa")), "an unverified archive must not be downloaded into the output directory");
            // verification happens after the small manifest blob and before the archive is requested
            assertEquals(before + 1, registry.blobGets.get(), "only the manifest blob may be requested, never the archive");
        }
    }

    @Test
    void aKeyThatIsNotTrustedIsRefused() throws Exception {
        try (var registry = new FakeRegistry()) {
            SigningKey attacker = SigningKey.generate();
            Path file = archive("s".repeat(5_000));
            ArchiveManifest manifest = manifestFor(file);
            store(registry, "test/archives").withSigningKey(attacker).put(manifest, file);
            var pod = store(registry, "test/archives").withVerifier(trusting(SigningKey.generate()));
            var ex = assertThrows(StoreException.class, () -> pod.get(manifest.fingerprint(), tmp.resolve("o5")));
            assertTrue(ex.getMessage().contains("not trusted") && ex.getMessage().contains(attacker.keyId()), ex.getMessage());
        }
    }

    // a key list was given but nothing in it is usable (a typo): everything is refused, the check is never switched off
    @Test
    void aVerifierWithNoUsableKeyRefusesEvenSignedArchives() throws Exception {
        try (var registry = new FakeRegistry()) {
            SigningKey key = SigningKey.generate();
            Path file = archive("s".repeat(5_000));
            ArchiveManifest manifest = manifestFor(file);
            store(registry, "test/archives").withSigningKey(key).put(manifest, file);
            var noKeys = ArchiveVerifier.fromEntries("garbage-entry", n -> { });
            assertTrue(noKeys.isEmpty());
            var pod = store(registry, "test/archives").withVerifier(noKeys);
            assertThrows(StoreException.class, () -> pod.get(manifest.fingerprint(), tmp.resolve("o6")));
        }
    }

    @Test
    void aTamperedSignatureIsRefused() throws Exception {
        try (var registry = new FakeRegistry()) {
            SigningKey key = SigningKey.generate();
            Path file = archive("s".repeat(5_000));
            ArchiveManifest manifest = manifestFor(file);
            store(registry, "test/archives").withSigningKey(key).put(manifest, file);
            Map<String, Object> image = imageOf(registry, tagOf(manifest));
            @SuppressWarnings("unchecked")
            Map<String, Object> annotations = (Map<String, Object>) image.get("annotations");
            byte[] sig = java.util.Base64.getDecoder().decode((String) annotations.get(OciArchiveStore.ANNOTATION_SIGNATURE));
            sig[5] ^= 1;
            annotations.put(OciArchiveStore.ANNOTATION_SIGNATURE, java.util.Base64.getEncoder().encodeToString(sig));
            storeImage(registry, tagOf(manifest), image);
            var pod = store(registry, "test/archives").withVerifier(trusting(key));
            var ex = assertThrows(StoreException.class, () -> pod.get(manifest.fingerprint(), tmp.resolve("o7")));
            assertTrue(ex.getMessage().contains("does not match"), ex.getMessage());
        }
    }

    // the replay from the design: a valid signed entry copied into another repository must not be accepted there
    @Test
    void aSignedArchiveCopiedIntoAnotherRepositoryIsRefused() throws Exception {
        try (var registry = new FakeRegistry()) {
            SigningKey key = SigningKey.generate();
            Path file = archive("s".repeat(5_000));
            ArchiveManifest manifest = manifestFor(file);
            store(registry, "team/billing").withSigningKey(key).put(manifest, file);
            // the fake registry serves one set of blobs and manifests for any repository name, which is exactly a verbatim copy
            assertTrue(store(registry, "team/billing").withVerifier(trusting(key)).get(manifest.fingerprint(), tmp.resolve("o8")).isPresent());
            var orders = store(registry, "team/orders").withVerifier(trusting(key));
            var ex = assertThrows(StoreException.class, () -> orders.get(manifest.fingerprint(), tmp.resolve("o9")));
            assertTrue(ex.getMessage().contains("does not match"), ex.getMessage());
        }
    }

    // an attacker who can write the manifest points it at their own archive and keeps the genuine signature
    @Test
    void aManifestPointingAtAnotherArchiveKeepsNoValidSignature() throws Exception {
        try (var registry = new FakeRegistry()) {
            SigningKey key = SigningKey.generate();
            Path file = archive("s".repeat(5_000));
            ArchiveManifest manifest = manifestFor(file);
            store(registry, "test/archives").withSigningKey(key).put(manifest, file);
            byte[] evil = "e".repeat(5_000).getBytes(StandardCharsets.UTF_8);
            String evilDigest = "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(evil));
            registry.blobs.put(evilDigest, evil);
            Map<String, Object> image = imageOf(registry, tagOf(manifest));
            @SuppressWarnings("unchecked")
            Map<String, Object> layer = (Map<String, Object>) ((List<Object>) image.get("layers")).get(0);
            layer.put("digest", evilDigest);
            storeImage(registry, tagOf(manifest), image);
            var pod = store(registry, "test/archives").withVerifier(trusting(key));
            var ex = assertThrows(StoreException.class, () -> pod.get(manifest.fingerprint(), tmp.resolve("o10")));
            assertTrue(ex.getMessage().contains("does not match"), ex.getMessage());
        }
    }

    // the trainer's check: an entry not signed by its own key is incomplete, so it is re-published (first rollout, key rotation)
    @Test
    void theTrainerRepublishesAnEntryItsKeyDidNotSign() throws Exception {
        try (var registry = new FakeRegistry()) {
            Path file = archive("s".repeat(5_000));
            ArchiveManifest manifest = manifestFor(file);
            store(registry, "test/archives").put(manifest, file); // published before signing was switched on
            SigningKey key = SigningKey.generate();
            var trainer = store(registry, "test/archives").withSigningKey(key).withVerifier(trusting(key));
            var inspection = trainer.inspect(manifest.fingerprint());
            assertEquals(ArchiveStore.Presence.BROKEN, inspection.presence());
            assertTrue(inspection.detail().contains("not signed"), inspection.detail());

            // the orchestrator treats that as work to do, and the result is signed
            var outcome = new TrainingOrchestrator().ensure(manifest, trainer, TrainingLock.none(), () -> file);
            assertEquals(TrainingOrchestrator.Status.TRAINED, outcome.status(), outcome.detail());
            assertEquals(ArchiveStore.Presence.USABLE, trainer.inspect(manifest.fingerprint()).presence());
            assertEquals(TrainingOrchestrator.Status.ALREADY_STORED,
                    new TrainingOrchestrator().ensure(manifest, trainer, TrainingLock.none(), () -> file).status());

            // rotation: a new key does not recognise the old signature, so the entry is re-published under the new key
            SigningKey next = SigningKey.generate();
            var rotated = store(registry, "test/archives").withSigningKey(next).withVerifier(trusting(next));
            assertEquals(ArchiveStore.Presence.BROKEN, rotated.inspect(manifest.fingerprint()).presence());
            assertEquals(TrainingOrchestrator.Status.TRAINED,
                    new TrainingOrchestrator().ensure(manifest, rotated, TrainingLock.none(), () -> file).status());
            // pods that list both keys accept it, and a pod that lists only the old key does not
            assertTrue(store(registry, "test/archives").withVerifier(trusting(key, next)).get(manifest.fingerprint(), tmp.resolve("o11")).isPresent());
            assertThrows(StoreException.class, () -> store(registry, "test/archives").withVerifier(trusting(key)).get(manifest.fingerprint(), tmp.resolve("o12")));
        }
    }

    // ---- what the fetcher leaves on disk ----

    @Test
    void theFetcherWritesTheSignatureFileAndTheManifestUnchangedAndWithdrawsThemOnAMiss() throws Exception {
        try (var registry = new FakeRegistry()) {
            SigningKey key = SigningKey.generate();
            Path file = archive("s".repeat(5_000));
            ArchiveManifest manifest = manifestFor(file);
            store(registry, "test/archives").withSigningKey(key).put(manifest, file);
            Path out = tmp.resolve("cache");
            var pod = store(registry, "test/archives").withVerifier(trusting(key));
            var result = new ArchiveFetcher().fetch(pod, List.of(manifest), out);
            assertEquals(ArchiveFetcher.Outcome.HIT, result.outcome(), result.notes().toString());
            var parsed = SignatureFile.parse(Files.readString(out.resolve(ArchiveFetcher.SIGNATURE_FILE), StandardCharsets.UTF_8));
            assertEquals(key.keyId(), parsed.signature().keyId());
            assertEquals("test/archives", parsed.repository());
            byte[] configBlob = registry.blobs.values().stream().filter(b -> b.length > 0 && b[0] == '{').findFirst().orElseThrow();
            assertArrayEquals(configBlob, Files.readAllBytes(out.resolve(ArchiveManifest.FILE_NAME)));
            assertTrue(Files.exists(out.resolve(ArchiveFetcher.FLAGS_FILE)));

            // the next fetch finds nothing it will trust: everything the previous one published is withdrawn
            var stranger = store(registry, "test/archives").withVerifier(trusting(SigningKey.generate()));
            var miss = new ArchiveFetcher().fetch(stranger, List.of(manifest), out);
            assertEquals(ArchiveFetcher.Outcome.MISS, miss.outcome());
            assertTrue(miss.notes().get(0).contains("not trusted"), miss.notes().toString());
            for (String leftover : List.of(ArchiveFetcher.FLAGS_FILE, ArchiveManifest.FILE_NAME, ArchiveFetcher.SIGNATURE_FILE)) {
                assertFalse(Files.exists(out.resolve(leftover)), leftover + " must not outlive a miss");
            }
        }
    }

    @Test
    void anUnsignedStoreLeavesNoSignatureFile() throws Exception {
        try (var registry = new FakeRegistry()) {
            Path file = archive("s".repeat(5_000));
            ArchiveManifest manifest = manifestFor(file);
            store(registry, "test/archives").put(manifest, file);
            Path out = tmp.resolve("cache2");
            var result = new ArchiveFetcher().fetch(store(registry, "test/archives"), List.of(manifest), out);
            assertEquals(ArchiveFetcher.Outcome.HIT, result.outcome());
            assertFalse(Files.exists(out.resolve(ArchiveFetcher.SIGNATURE_FILE)));
        }
    }
}
