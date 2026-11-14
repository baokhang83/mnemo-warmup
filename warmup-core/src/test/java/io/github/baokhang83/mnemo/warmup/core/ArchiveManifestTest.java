package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warmup.core.JarExtractor.ExtractedApp;
import io.github.baokhang83.mnemo.warmup.core.JarExtractor.Kind;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveManifestTest {

    @TempDir
    Path dir;

    private static final JdkInfo JDK = new JdkInfo(Path.of("/jdk"), "25.0.1", 25, "Acme", "25.0.1+8");

    /** An extracted-Spring-Boot-like layout: app.jar whose Class-Path lists lib/a.jar and lib/b.jar. */
    private ExtractedApp layout(Path root, String contentA, String contentB) throws IOException {
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/a.jar"), contentA);
        Files.writeString(root.resolve("lib/b.jar"), contentB);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "lib/a.jar lib/b.jar");
        Path jar = root.resolve("app.jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out, manifest)) {
            // manifest only
        }
        return new ExtractedApp(Kind.SPRING_BOOT, jar);
    }

    private ArchiveManifest describe(ExtractedApp app, JdkInfo jdk, CacheStrategy strategy, List<String> args, String digest) {
        return ArchiveManifest.describe(app, jdk, strategy, args, Map.of(), digest);
    }

    private ArchiveManifest base(ExtractedApp app) {
        return describe(app, JDK, CacheStrategy.LEYDEN_AOT, List.of("-Xmx512m"), "sha256:img");
    }

    private static String sha256Hex(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    @Test
    void recordsTheClasspathInOrderWithSizeAndHash() throws Exception {
        ExtractedApp app = layout(dir, "AAA", "BBBB");

        ArchiveManifest m = base(app);

        assertEquals(List.of("app.jar", "lib/a.jar", "lib/b.jar"),
                m.classpath().stream().map(ArchiveManifest.ClasspathEntry::name).toList());
        var a = m.classpath().get(1);
        assertEquals(3, a.sizeBytes());
        assertEquals(sha256Hex(dir.resolve("lib/a.jar")), a.sha256());
        assertEquals(4, m.classpath().get(2).sizeBytes());
        assertEquals(List.of("-Xmx512m"), m.jvmFlags());
        assertNull(m.archive());
    }

    @Test
    void theFingerprintIsStableAndWellFormed() throws Exception {
        ExtractedApp app = layout(dir, "AAA", "BBBB");
        String fp = base(app).fingerprint();
        assertEquals(fp, base(app).fingerprint());
        assertTrue(fp.matches("sha256:[0-9a-f]{64}"), fp);
    }

    @Test
    void softMaximumChangesReuseTheSameFingerprintAndManifest() throws Exception {
        ExtractedApp app = layout(dir, "AAA", "BBBB");
        ArchiveManifest trained = describe(app, JDK, CacheStrategy.LEYDEN_AOT,
                List.of("-Xmx512m", "-XX:SoftMaxHeapSize=128m"), "sha256:img");
        ArchiveManifest live = describe(app, JDK, CacheStrategy.LEYDEN_AOT,
                List.of("-Xmx512m", "-XX:SoftMaxHeapSize=256m"), "sha256:img");
        assertEquals(trained.fingerprint(), live.fingerprint());
        assertEquals(List.of(), trained.mismatches(live));
    }

    @Test
    void theFingerprintIsTheSameForTheSameContentInAnotherDirectory() throws Exception {
        ExtractedApp one = layout(Files.createDirectory(dir.resolve("one")), "AAA", "BBBB");
        ExtractedApp two = layout(Files.createDirectory(dir.resolve("two")), "AAA", "BBBB");
        assertEquals(base(one).fingerprint(), base(two).fingerprint());
    }

    @Test
    void everyInputThatMattersChangesTheFingerprint() throws Exception {
        ExtractedApp app = layout(dir, "AAA", "BBBB");
        String base = base(app).fingerprint();

        List<String> changed = List.of(
                describe(app, JDK, CacheStrategy.APP_CDS, List.of("-Xmx512m"), "sha256:img").fingerprint(),
                describe(app, new JdkInfo(JDK.javaHome(), "25.0.1", 25, "Other", "25.0.1+8"), CacheStrategy.LEYDEN_AOT,
                        List.of("-Xmx512m"), "sha256:img").fingerprint(),
                describe(app, new JdkInfo(JDK.javaHome(), "25.0.2", 25, "Acme", "25.0.2+1"), CacheStrategy.LEYDEN_AOT,
                        List.of("-Xmx512m"), "sha256:img").fingerprint(),
                describe(app, JDK, CacheStrategy.LEYDEN_AOT, List.of("-Xmx1g"), "sha256:img").fingerprint(),
                describe(app, JDK, CacheStrategy.LEYDEN_AOT, List.of("-Xmx512m", "-XX:+UseZGC"), "sha256:img").fingerprint(),
                describe(app, JDK, CacheStrategy.LEYDEN_AOT, List.of("-Xmx512m"), "sha256:other").fingerprint(),
                describe(app, JDK, CacheStrategy.LEYDEN_AOT, List.of("-Xmx512m"), null).fingerprint());

        for (String fp : changed) {
            assertNotEquals(base, fp);
        }
        assertEquals(changed.size(), changed.stream().distinct().count(), "each change gives its own fingerprint");
    }

    @Test
    void classpathContentSizeAndOrderChangeTheFingerprint() throws Exception {
        String base = base(layout(Files.createDirectory(dir.resolve("base")), "AAA", "BBBB")).fingerprint();
        String content = base(layout(Files.createDirectory(dir.resolve("c")), "AAX", "BBBB")).fingerprint();
        String size = base(layout(Files.createDirectory(dir.resolve("s")), "AAAA", "BBBB")).fingerprint();
        String swapped = base(layout(Files.createDirectory(dir.resolve("w")), "BBBB", "AAA")).fingerprint();
        assertEquals(4, List.of(base, content, size, swapped).stream().distinct().count());
    }

    @Test
    void irrelevantThingsDoNotChangeTheFingerprint() throws Exception {
        ExtractedApp app = layout(dir, "AAA", "BBBB");
        String base = base(app).fingerprint();

        String otherArgs = describe(app, JDK, CacheStrategy.LEYDEN_AOT,
                List.of("-Dfoo=bar", "-Xmx512m", "-verbose:gc"), "sha256:img").fingerprint();
        // the same JDK found at another path, and a re-extraction with new file times
        String otherHome = describe(app, new JdkInfo(Path.of("/elsewhere"), "25.0.1", 25, "Acme", "25.0.1+8"),
                CacheStrategy.LEYDEN_AOT, List.of("-Xmx512m"), "sha256:img").fingerprint();
        Files.setLastModifiedTime(dir.resolve("lib/a.jar"), FileTime.from(Instant.parse("2001-01-01T00:00:00Z")));

        assertEquals(base, otherArgs);
        assertEquals(base, otherHome);
        assertEquals(base, base(app).fingerprint());
    }

    @Test
    void jsonRoundTripPreservesEverythingIncludingTheArchive() throws Exception {
        ExtractedApp app = layout(dir, "AAA", "BBBB");
        Path archive = Files.writeString(dir.resolve("app.aot"), "archive bytes");
        ArchiveManifest m = base(app).withArchive(archive);

        Path file = dir.resolve(ArchiveManifest.FILE_NAME);
        m.write(file);
        ArchiveManifest back = ArchiveManifest.read(file);

        assertEquals(m, back);
        assertEquals(m.fingerprint(), back.fingerprint());
        assertEquals("app.aot", back.archive().file());
        assertEquals(13, back.archive().sizeBytes());
        assertEquals(sha256Hex(archive), back.archive().sha256());
        assertTrue(Files.readString(file).contains("\"fingerprint\": \"" + m.fingerprint() + "\""));
    }

    @Test
    void aManifestWithoutAnImageDigestRoundTrips() throws Exception {
        ArchiveManifest m = describe(layout(dir, "AAA", "BBBB"), JDK, CacheStrategy.APP_CDS, List.of(), null);
        assertEquals(m, ArchiveManifest.fromJson(m.toJson()));
        assertNull(ArchiveManifest.fromJson(m.toJson()).imageDigest());
    }

    @Test
    void anEditedManifestIsRejected() throws Exception {
        String json = base(layout(dir, "AAA", "BBBB")).toJson();

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> ArchiveManifest.fromJson(json.replace("-Xmx512m", "-Xmx9g")));

        assertTrue(e.getMessage().contains("does not match"), e.getMessage());
    }

    @Test
    void malformedManifestsAreRejectedWithAClearMessage() throws Exception {
        String json = base(layout(dir, "AAA", "BBBB")).toJson();
        assertThrows(IllegalArgumentException.class, () -> ArchiveManifest.fromJson("[]"));
        assertThrows(IllegalArgumentException.class, () -> ArchiveManifest.fromJson("not json"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ArchiveManifest.fromJson(json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")))
                .getMessage().contains("schemaVersion"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ArchiveManifest.fromJson(json.replace("\"cacheType\"", "\"cacheKind\"")))
                .getMessage().contains("cacheType"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ArchiveManifest.fromJson(json.replace("LEYDEN_AOT", "MAGIC")))
                .getMessage().contains("MAGIC"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ArchiveManifest.fromJson(json.replace("\"sizeBytes\": 3", "\"sizeBytes\": \"3\"")))
                .getMessage().contains("sizeBytes"));
    }

    @Test
    void identicalEnvironmentsHaveNoMismatches() throws Exception {
        ExtractedApp app = layout(dir, "AAA", "BBBB");
        assertEquals(List.of(), base(app).mismatches(base(app)));
    }

    @Test
    void mismatchesExplainWhatIsDifferent() throws Exception {
        ExtractedApp trained = layout(Files.createDirectory(dir.resolve("t")), "AAA", "BBBB");
        ExtractedApp live = layout(Files.createDirectory(dir.resolve("l")), "AAX", "BBBB");
        ArchiveManifest expected = base(trained);
        ArchiveManifest actual = describe(live, new JdkInfo(JDK.javaHome(), "25.0.2", 25, "Acme", "25.0.2+1"),
                CacheStrategy.APP_CDS, List.of("-Xmx1g"), "sha256:next");

        String all = String.join("\n", expected.mismatches(actual));

        assertTrue(all.contains("cache type LEYDEN_AOT vs APP_CDS"), all);
        assertTrue(all.contains("JDK build 25.0.1+8 vs 25.0.2+1"), all);
        assertTrue(all.contains("image digest sha256:img vs sha256:next"), all);
        assertTrue(all.contains("JVM options [-Xmx512m] vs [-Xmx1g]"), all);
        assertTrue(all.contains("'lib/a.jar' has different content"), all);
        assertFalse(all.contains("lib/b.jar"), all);
    }

    @Test
    void mismatchesNoticeClasspathLengthAndNames() throws Exception {
        ArchiveManifest expected = base(layout(Files.createDirectory(dir.resolve("t")), "AAA", "BBBB"));
        ArchiveManifest shorter = new ArchiveManifest(1, expected.cacheType(), expected.jdk(), expected.imageDigest(),
                expected.jvmFlags(), expected.classpath().subList(0, 2), null);
        assertTrue(String.join("\n", expected.mismatches(shorter)).contains("3 entries vs 2"));

        var renamed = new java.util.ArrayList<>(expected.classpath());
        renamed.set(1, new ArchiveManifest.ClasspathEntry("lib/z.jar", 3, "x", null));
        ArchiveManifest other = new ArchiveManifest(1, expected.cacheType(), expected.jdk(), expected.imageDigest(),
                expected.jvmFlags(), renamed, null);
        assertTrue(String.join("\n", expected.mismatches(other)).contains("'lib/a.jar' vs 'lib/z.jar'"));
    }

    @Test
    void aSingleJarWithoutClassPathIsJustItself() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        Path jar = dir.resolve("uber.jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out, manifest)) {
            // manifest only
        }
        var m = describe(new ExtractedApp(Kind.EXECUTABLE_JAR, jar), JDK, CacheStrategy.APP_CDS, List.of(), null);
        assertEquals(List.of("uber.jar"), m.classpath().stream().map(ArchiveManifest.ClasspathEntry::name).toList());
    }

    @Test
    void aMissingClassPathEntryIsAnError() throws Exception {
        ExtractedApp app = layout(dir, "AAA", "BBBB");
        Files.delete(dir.resolve("lib/b.jar"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> base(app));

        assertTrue(e.getMessage().contains("lib/b.jar"), e.getMessage());
    }

    @Test
    void unreadableManifestFilesAreReportedNotThrownRaw() {
        assertThrows(IllegalStateException.class, () -> ArchiveManifest.read(dir.resolve("nope.json")));
    }

    @Test
    void theManifestOfARealTrainingRunDescribesTheArchive() throws Exception {
        JdkInfo jdk = JdkInfo.current();
        CacheStrategy strategy = CacheType.AUTO.resolve(jdk);
        Path jar = TestApps.jar(dir, "spring-like", TestApps.EXITS_ON_PROPERTY);
        ExtractedApp app = new ExtractedApp(Kind.SPRING_BOOT, jar);
        Path archive = dir.resolve("app." + strategy.extension());
        List<String> jvmArgs = List.of("-Xmx256m");

        new AppTrainer().train(app, jdk, strategy, new SpringRefreshExitMode(), archive, Duration.ofSeconds(60), jvmArgs);
        ArchiveManifest manifest = ArchiveManifest.describe(app, jdk, strategy, jvmArgs, Map.of(), null).withArchive(archive);

        assertEquals(Files.size(archive), manifest.archive().sizeBytes());
        assertEquals(sha256Hex(archive), manifest.archive().sha256());
        assertEquals(List.of("-Xmx256m"), manifest.jvmFlags());
        assertEquals(jdk.runtimeVersion(), manifest.jdk().runtimeVersion());
        // what a later step would compute in the same environment matches
        assertEquals(List.of(), manifest.mismatches(ArchiveManifest.describe(app, jdk, strategy, jvmArgs, Map.of(), null)));
    }

    @Test
    void launchDirSurvivesJsonAndIsNotPartOfTheFingerprint() {
        var base = new ArchiveManifest(
                ArchiveManifest.SCHEMA_VERSION, CacheStrategy.APP_CDS, new ArchiveManifest.Jdk("V", "21.0.5", "21.0.5+11", 21),
                null, List.of(), List.of(new ArchiveManifest.ClasspathEntry("app.jar", 1, "ab".repeat(32), Instant.EPOCH)), null, "/app");
        var elsewhere = new ArchiveManifest(
                base.schemaVersion(), base.cacheType(), base.jdk(), null, base.jvmFlags(), base.classpath(), null, "/other");

        assertEquals("/app", ArchiveManifest.fromJson(base.toJson()).launchDir());
        assertEquals(base.fingerprint(), elsewhere.fingerprint());
        assertEquals(List.of(), base.locationMismatches(base));
        assertEquals(1, base.locationMismatches(elsewhere).size());
    }

    @Test
    void aManifestWithoutALaunchDirCannotBeLocationChecked() {
        var legacy = new ArchiveManifest(
                ArchiveManifest.SCHEMA_VERSION, CacheStrategy.APP_CDS, new ArchiveManifest.Jdk("V", "21.0.5", "21.0.5+11", 21),
                null, List.of(), List.of(), null);
        assertNull(ArchiveManifest.fromJson(legacy.toJson()).launchDir());
        assertEquals(1, legacy.locationMismatches(legacy).size());
    }
}
