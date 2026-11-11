package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The Go launcher's native run planner (launcher/planner.go, docs/adr/0010) is tested against a fixture that this code wrote
 * (launcher/testdata/compat/Gen.java). This test is the other half: it fails when Java no longer accepts the committed manifest,
 * which means the fingerprint changed and the fixture, and the Go planner, must be brought in line. Regenerate with the
 * command in Gen.java, then change launcher/planner.go if its tests fail.
 */
class CompatFixtureTest {

    private static final Path FIXTURE = Path.of("..", "launcher", "testdata", "compat", "fixture");

    @Test
    void javaStillAcceptsTheFixtureTheGoPlannerIsTestedWith() throws Exception {
        Path manifestFile = FIXTURE.resolve("cache").resolve(ArchiveManifest.FILE_NAME);
        assumeTrue(Files.isRegularFile(manifestFile), "the launcher directory is not part of this checkout");

        // fromJson recomputes the fingerprint from the contents and rejects a manifest that does not match its own.
        ArchiveManifest manifest = ArchiveManifest.fromJson(Files.readString(manifestFile, StandardCharsets.UTF_8));

        assertEquals(2, manifest.classpath().size());
        Path appDir = FIXTURE.resolve("app");
        for (ArchiveManifest.ClasspathEntry entry : manifest.classpath()) {
            Path file = appDir.resolve(entry.name());
            assertEquals(entry.sizeBytes(), Files.size(file), entry.name() + " was regenerated without the manifest");
            assertEquals(entry.sha256(), sha256(file), entry.name() + " was regenerated without the manifest");
        }
        assertEquals(manifest.archive().sha256(), sha256(FIXTURE.resolve("cache").resolve("app.jsa")));
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
