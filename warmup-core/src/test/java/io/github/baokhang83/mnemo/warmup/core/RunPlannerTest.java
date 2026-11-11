package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunPlannerTest {

    private static final JdkInfo JDK = JdkInfo.fromJavaHome(Path.of(System.getProperty("java.home")));
    private static final CacheStrategy STRATEGY = CacheType.AUTO.resolve(JDK);
    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    private Path templateJar;

    @BeforeAll
    void buildTemplate(@TempDir Path shared) throws IOException {
        templateJar = TestApps.jar(shared, "template", TestApps.HELLO);
    }

    /** A fetched cache directory plus the jar it describes, laid out as fetch + the image would leave them. */
    private record Cache(Path dir, Path jar, Path cacheDir, ArchiveManifest manifest) {}

    private Cache cache(Path dir) throws IOException {
        Path jar = Files.copy(templateJar, dir.resolve("app.jar"));
        Files.setLastModifiedTime(jar, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Path cacheDir = Files.createDirectories(dir.resolve("cache"));
        Path archive = Files.writeString(cacheDir.resolve("app." + STRATEGY.extension()), "archive");
        var manifest = ArchiveManifest.describe(
                        new JarExtractor.ExtractedApp(JarExtractor.Kind.EXECUTABLE_JAR, jar), JDK, STRATEGY, List.of("-Xmx512m"), Map.of(), null)
                .withArchive(archive);
        manifest.write(cacheDir.resolve(ArchiveManifest.FILE_NAME));
        Files.writeString(cacheDir.resolve(ArchiveFetcher.FLAGS_FILE), STRATEGY.runtimeFlag(archive.toAbsolutePath().toString()) + "\n");
        return new Cache(dir, jar, cacheDir, manifest);
    }

    private static List<String> launch(Cache c, String... jvmOptions) {
        List<String> command = new ArrayList<>(List.of(JAVA));
        command.addAll(List.of(jvmOptions));
        command.addAll(List.of("-jar", c.jar().toString(), "--server.port=8080"));
        return command;
    }

    private RunPlanner.Plan plan(Cache c, List<String> command) {
        return new RunPlanner().plan(command, c.cacheDir(), Map.of(), null);
    }

    @Test
    void aMatchingLaunchGetsTheFlagRightAfterJava(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        var plan = plan(c, launch(c, "-Xmx512m"));

        assertTrue(plan.archiveUsed(), plan.notes().toString());
        assertEquals(JAVA, plan.command().get(0));
        assertTrue(plan.command().get(1).startsWith(STRATEGY.runtimeFlag("")), plan.command().toString());
        assertEquals(List.of("-Xmx512m", "-jar"), plan.command().subList(2, 4));
        assertEquals("--server.port=8080", plan.command().get(plan.command().size() - 1));
    }

    @Test
    void timingIsReportedOnlyWhenAskedFor(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        var quiet = plan(c, launch(c, "-Xmx512m"));
        assertTrue(quiet.notes().stream().noneMatch(n -> n.startsWith("timing:")), quiet.notes().toString());

        var timed = new RunPlanner().plan(launch(c, "-Xmx512m"), c.cacheDir(), Map.of(PhaseTimer.ENV, "1"), null);
        assertTrue(timed.archiveUsed(), timed.notes().toString());
        String line = timed.notes().stream().filter(n -> n.startsWith("timing:")).findFirst().orElseThrow();
        for (String phase : List.of("read-manifest=", "archive-sha256=", "jdk-probe=", "classpath-hash=", "tool-jvm-uptime=")) {
            assertTrue(line.contains(phase), phase + " missing in: " + line);
        }
    }

    @Test
    void optionsThatDoNotAffectTheArchiveAreIgnored(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        assertTrue(plan(c, launch(c, "-Xmx512m", "-Dfoo=bar", "-Xlog:gc")).archiveUsed());
    }

    @Test
    void differentHeapFlagsFallBackCleanlyAndSayWhy(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        var plan = plan(c, launch(c, "-Xmx1g"));

        assertFalse(plan.archiveUsed());
        assertEquals(launch(c, "-Xmx1g"), plan.command(), "the command must be unchanged");
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("-Xmx512m") && n.contains("-Xmx1g")), plan.notes().toString());
    }

    @Test
    void aChangedClasspathJarFallsBack(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        // a different but still valid JAR, with the training-time modification time, so only the content differs
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Other");
        try (OutputStream out = Files.newOutputStream(c.jar()); JarOutputStream ignored = new JarOutputStream(out, manifest)) {
            // no entries
        }
        Files.setLastModifiedTime(c.jar(), FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        var plan = plan(c, launch(c, "-Xmx512m"));
        assertFalse(plan.archiveUsed());
        assertTrue(plan.notes().get(0).contains("does not match"), plan.notes().toString());
    }

    @Test
    void differentJarModificationTimesFallBackEvenThoughTheFingerprintMatches(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        Files.setLastModifiedTime(c.jar(), FileTime.from(Instant.parse("2026-06-01T00:00:00Z")));
        var plan = plan(c, launch(c, "-Xmx512m"));

        assertFalse(plan.archiveUsed());
        assertTrue(plan.notes().get(0).contains("modification times"), plan.notes().toString());
    }

    @Test
    void noFlagsFileMeansPlain(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        Files.delete(c.cacheDir().resolve(ArchiveFetcher.FLAGS_FILE));
        var plan = plan(c, launch(c, "-Xmx512m"));
        assertFalse(plan.archiveUsed());
        assertTrue(plan.notes().get(0).contains("no archive was fetched"));
    }

    @Test
    void anEmptyFlagsFileMeansPlain(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        Files.writeString(c.cacheDir().resolve(ArchiveFetcher.FLAGS_FILE), "");
        assertFalse(plan(c, launch(c, "-Xmx512m")).archiveUsed());
    }

    @Test
    void aMissingManifestOrArchiveMeansPlain(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        Files.delete(c.cacheDir().resolve(ArchiveManifest.FILE_NAME));
        assertFalse(plan(c, launch(c, "-Xmx512m")).archiveUsed());

        Cache d = cache(Files.createDirectories(dir.resolve("again")));
        Files.delete(d.cacheDir().resolve("app." + STRATEGY.extension()));
        var plan = plan(d, launch(d, "-Xmx512m"));
        assertFalse(plan.archiveUsed());
        assertTrue(plan.notes().get(0).contains("missing or its size differs"), plan.notes().toString());
    }

    @Test
    void anArchiveWithTheWrongSizeIsNotUsed(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        Files.writeString(c.cacheDir().resolve("app." + STRATEGY.extension()), "different length archive");
        assertFalse(plan(c, launch(c, "-Xmx512m")).archiveUsed());
    }

    @Test
    void flagsThatDisagreeWithTheManifestCacheTypeAreNotUsed(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        String other = STRATEGY == CacheStrategy.APP_CDS ? "-XX:AOTCache=" : "-XX:SharedArchiveFile=";
        Files.writeString(c.cacheDir().resolve(ArchiveFetcher.FLAGS_FILE), other + c.cacheDir().resolve("app." + STRATEGY.extension()) + "\n");
        var plan = plan(c, launch(c, "-Xmx512m"));
        assertFalse(plan.archiveUsed());
        assertTrue(plan.notes().get(0).contains("do not match the manifest"), plan.notes().toString());
    }

    @Test
    void garbageInTheCacheNeverThrows(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        Files.writeString(c.cacheDir().resolve(ArchiveManifest.FILE_NAME), "{ not json");
        var plan = plan(c, launch(c, "-Xmx512m"));
        assertFalse(plan.archiveUsed());
        assertEquals(launch(c, "-Xmx512m"), plan.command());
        assertTrue(plan.notes().get(0).contains("could not validate"), plan.notes().toString());
    }

    @Test
    void launchesThatAreNotJarLaunchesAreLeftAlone(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        for (List<String> command : List.of(
                List.of(JAVA, "-cp", c.jar().toString(), "Main"),
                List.of(JAVA, "Main"),
                List.of("/bin/echo", "-jar", c.jar().toString()),
                List.of(JAVA, "-jar"))) {
            var plan = plan(c, command);
            assertFalse(plan.archiveUsed(), command.toString());
            assertEquals(command, plan.command());
        }
    }

    @Test
    void ifTheCommandAlreadySetsAnArchiveFlagItIsLeftAlone(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        var plan = plan(c, launch(c, "-Xmx512m", "-XX:SharedArchiveFile=/other.jsa"));
        assertFalse(plan.archiveUsed());
        assertTrue(plan.notes().get(0).contains("already sets"), plan.notes().toString());
    }

    @Test
    void aDirectlyLaunchedSpringBootFatJarGetsAnExtractionHint(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Spring-Boot-Version", "3.4.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "org.springframework.boot.loader.launch.JarLauncher");
        try (OutputStream out = Files.newOutputStream(c.jar()); JarOutputStream ignored = new JarOutputStream(out, manifest)) {
            // contents do not matter
        }
        var plan = plan(c, launch(c, "-Xmx512m"));
        assertFalse(plan.archiveUsed());
        assertTrue(plan.notes().get(0).contains("extract at image build time"), plan.notes().toString());
    }

    @Test
    void modificationTimesAreComparedToWholeSeconds(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        Files.setLastModifiedTime(c.jar(), FileTime.from(Instant.parse("2026-01-01T00:00:00.700Z")));
        assertTrue(plan(c, launch(c, "-Xmx512m")).archiveUsed());
    }

    /** The planned command, run for real: the JVM maps the archive. */
    @Test
    void theJvmActuallyUsesTheArchiveItWasPlannedWith(@TempDir Path dir) throws Exception {
        Path jar = TestApps.jar(dir, "e2e", TestApps.EXITS_ON_PROPERTY);
        Path cacheDir = Files.createDirectories(dir.resolve("cache"));
        Path archive = cacheDir.resolve("app." + STRATEGY.extension());
        var app = new JarExtractor.ExtractedApp(JarExtractor.Kind.SPRING_BOOT, jar);
        new AppTrainer().train(app, JDK, STRATEGY, new SpringRefreshExitMode(), archive, Duration.ofSeconds(120));
        var manifest = ArchiveManifest.describe(
                        new JarExtractor.ExtractedApp(JarExtractor.Kind.EXECUTABLE_JAR, jar), JDK, STRATEGY, List.of(), Map.of(), null)
                .withArchive(archive);
        manifest.write(cacheDir.resolve(ArchiveManifest.FILE_NAME));
        Files.writeString(cacheDir.resolve(ArchiveFetcher.FLAGS_FILE), STRATEGY.runtimeFlag(archive.toAbsolutePath().toString()) + "\n");

        List<String> command = List.of(JAVA, "-Dspring.context.exit=onRefresh", "-Xlog:class+load", "-jar", jar.toString());
        var plan = new RunPlanner().plan(command, cacheDir, Map.of(), null);
        assertTrue(plan.archiveUsed(), plan.notes().toString());

        Process process = new ProcessBuilder(plan.command()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("refreshed"), output);
        assertTrue(output.contains("Main source: shared objects file"), "the app class must come from the archive:\n" + output);
    }

    @Test
    void sameLengthCorruptionOfTheArchiveIsCaughtByItsHash(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        Path archive = c.cacheDir().resolve("app." + STRATEGY.extension());
        byte[] bytes = Files.readAllBytes(archive);
        bytes[0] ^= 0x01; // same length, different content
        Files.write(archive, bytes);

        var plan = plan(c, launch(c, "-Xmx512m"));

        assertFalse(plan.archiveUsed());
        assertTrue(plan.notes().get(0).contains("SHA-256"), plan.notes().toString());
        assertEquals(launch(c, "-Xmx512m"), plan.command());
    }

    @Test
    void identicalFilesInAnotherDirectoryAreNotUsedBecauseTheJvmRecordsPaths(@TempDir Path dir) throws Exception {
        Cache c = cache(Files.createDirectories(dir.resolve("a")));
        Path otherDir = Files.createDirectories(dir.resolve("b"));
        Path copy = Files.copy(c.jar(), otherDir.resolve("app.jar"), StandardCopyOption.COPY_ATTRIBUTES);
        assertEquals(Files.getLastModifiedTime(c.jar()), Files.getLastModifiedTime(copy));

        List<String> command = List.of(JAVA, "-Xmx512m", "-jar", copy.toString());
        var plan = new RunPlanner().plan(command, c.cacheDir(), Map.of(), null);

        assertFalse(plan.archiveUsed());
        assertTrue(plan.notes().get(0).contains("trained from") && plan.notes().get(0).contains("launched from"), plan.notes().toString());
        assertEquals(command, plan.command());
    }

    @Test
    void aManifestThatDoesNotRecordWhereItWasTrainedCannotBeVerified(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        var m = c.manifest();
        new ArchiveManifest(m.schemaVersion(), m.cacheType(), m.jdk(), m.imageDigest(), m.jvmFlags(), m.classpath(), m.archive())
                .write(c.cacheDir().resolve(ArchiveManifest.FILE_NAME));

        var plan = plan(c, launch(c, "-Xmx512m"));

        assertFalse(plan.archiveUsed());
        assertTrue(plan.notes().get(0).contains("does not record where it was trained"), plan.notes().toString());
    }

    @Test
    void aHungJdkProbeFallsBackInsteadOfBlockingTheLaunch(@TempDir Path dir) throws Exception {
        assumeShell();
        Cache c = cache(dir);
        // a "java" that never answers; the planner must give up on its own and start the command as given
        Path fakeHome = Files.createDirectories(dir.resolve("fake").resolve("bin"));
        Path fakeJava = Files.writeString(fakeHome.resolve("java"), "#!/bin/sh\nexec sleep 60\n");
        fakeJava.toFile().setExecutable(true);
        List<String> command = List.of(fakeJava.toString(), "-Xmx512m", "-jar", c.jar().toString());

        long started = System.nanoTime();
        var plan = new RunPlanner().plan(command, c.cacheDir(), Map.of(), null);
        long seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds();

        assertFalse(plan.archiveUsed());
        assertEquals(command, plan.command());
        assertTrue(plan.notes().get(0).contains("could not validate") && plan.notes().get(0).contains("Timed out"), plan.notes().toString());
        assertTrue(seconds < 30, "the probe must be bounded, took " + seconds + " s");
    }

    private static void assumeShell() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "needs /bin/sh");
    }
}
