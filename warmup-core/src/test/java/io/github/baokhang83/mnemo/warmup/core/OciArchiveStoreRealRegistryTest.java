package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round trip against a real distribution registry. Skipped unless {@code -Dwarmup.registry=host:port}
 * names a plain-HTTP, anonymous one, e.g. {@code docker run -p 5055:5000 registry:2}.
 */
class OciArchiveStoreRealRegistryTest {

    @TempDir
    Path tmp;

    @Test
    void roundTripsAgainstARealRegistry() throws Exception {
        String registry = System.getProperty("warmup.registry");
        assumeTrue(registry != null && !registry.isBlank(), "set -Dwarmup.registry=host:port to run");

        Path file = tmp.resolve("app.jsa");
        Files.write(file, new byte[3 * 1024 * 1024 + 17]);
        var manifest = new ArchiveManifest(
                        ArchiveManifest.SCHEMA_VERSION,
                        CacheStrategy.APP_CDS,
                        new ArchiveManifest.Jdk("Eclipse Adoptium", "21.0.5", "21.0.5+11", 21),
                        "sha256:" + java.util.UUID.randomUUID().toString().replace("-", "").repeat(2),
                        List.of(),
                        List.of(new ArchiveManifest.ClasspathEntry("app.jar", 1, "cd".repeat(32), java.time.Instant.EPOCH)),
                        null)
                .withArchive(file);
        var store = OciArchiveStore.of("oci://" + registry + "/mnemo/archives", true, null, Duration.ofSeconds(60));

        assertFalse(store.exists(manifest.fingerprint()));
        store.put(manifest, file);
        assertTrue(store.exists(manifest.fingerprint()));
        Path out = Files.createDirectory(tmp.resolve("out"));
        var got = store.get(manifest.fingerprint(), out).orElseThrow();
        assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(got.archiveFile()));
    }
}
