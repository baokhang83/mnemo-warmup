package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs real JVMs: AppCDS when the test JDK is 21-24, the AOT cache on 25+. */
class TrainingRunnerTest {

    @TempDir
    Path dir;

    private final JdkInfo jdk = JdkInfo.current();
    private final CacheStrategy auto = CacheType.AUTO.resolve(jdk);
    private final TrainingRunner runner = new TrainingRunner();

    private Path archive() {
        return dir.resolve("out").resolve("app." + auto.extension());
    }

    private TrainingSpec.Builder spec(String source, String name) throws IOException {
        Path jar = TestApps.jar(dir, name, source);
        return TrainingSpec.builder(jdk.javaExecutable(), auto, archive())
                .appArgs("-jar", jar.toString())
                .timeout(Duration.ofSeconds(60))
                .shutdownGrace(Duration.ofSeconds(60));
    }

    @Test
    void anApplicationThatExitsByItselfProducesAnArchive() throws IOException {
        var result = runner.run(spec(TestApps.HELLO, "hello").ready(ReadyCondition.processExits()).build());

        assertFalse(result.stoppedByUs());
        assertTrue(result.sizeBytes() > 0);
        assertEquals(archive(), result.archive());
        assertTrue(Files.readString(result.logFile()).contains("hello"));
    }

    @Test
    void aRunningApplicationIsStoppedWithSigtermAndStillProducesAnArchive() throws IOException {
        var result = runner.run(
                spec(TestApps.SLEEPER, "sleeper").ready(ReadyCondition.afterDelay(Duration.ofSeconds(2))).build());

        assertTrue(result.stoppedByUs());
        assertTrue(Files.size(result.archive()) > 0);
        assertTrue(Files.readString(result.logFile()).contains("up"));
    }

    @Test
    void afterDelayReturnsEarlyWhenTheApplicationExits() throws IOException {
        long start = System.nanoTime();
        var result = runner.run(spec(TestApps.HELLO, "hello").ready(ReadyCondition.afterDelay(Duration.ofSeconds(50))).build());

        assertFalse(result.stoppedByUs());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 40);
    }

    @Test
    void aReadyConditionThatNeverFiresTimesOut() throws IOException {
        var spec = spec(TestApps.SLEEPER, "sleeper")
                .ready(ReadyCondition.processExits())
                .timeout(Duration.ofSeconds(2))
                .build();

        TrainingException e = assertThrows(TrainingException.class, () -> runner.run(spec));

        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertEquals(spec.logFile(), e.logFile());
    }

    @Test
    void aCrashingApplicationReportsExitCodeAndOutput() throws IOException {
        var spec = spec(TestApps.CRASH, "crash").ready(ReadyCondition.processExits()).build();

        TrainingException e = assertThrows(TrainingException.class, () -> runner.run(spec));

        assertTrue(e.getMessage().contains("code 3"), e.getMessage());
        assertTrue(e.getMessage().contains("boom"), e.getMessage());
    }

    @Test
    void aMissingArchiveIsAnErrorEvenWhenTheApplicationSucceeds() throws IOException {
        // -Xshare:off makes the JVM skip the dynamic AppCDS dump: exit 0, no archive.
        Path jar = TestApps.jar(dir, "hello", TestApps.HELLO);
        var spec = TrainingSpec.builder(jdk.javaExecutable(), CacheStrategy.APP_CDS, dir.resolve("x.jsa"))
                .jvmArg("-Xshare:off")
                .appArgs("-jar", jar.toString())
                .build();

        TrainingException e = assertThrows(TrainingException.class, () -> runner.run(spec));

        assertTrue(e.getMessage().contains("no archive was written"), e.getMessage());
    }

    @Test
    void aFailedRunLeavesNoArchiveBehind() throws IOException {
        Files.createDirectories(archive().getParent());
        Files.writeString(archive(), "stale");
        var spec = spec(TestApps.CRASH, "crash").ready(ReadyCondition.processExits()).build();

        assertThrows(TrainingException.class, () -> runner.run(spec));

        // The stale file is gone, and so is the archive the crashing JVM wrote on its way out.
        assertFalse(Files.exists(archive()), "a failed run must not leave an archive");
    }

    @Test
    void anApplicationThatIgnoresSigtermIsKilledAndReported() throws IOException {
        var spec = spec(TestApps.STUBBORN, "stubborn")
                .ready(ReadyCondition.afterDelay(Duration.ofSeconds(2)))
                .shutdownGrace(Duration.ofSeconds(2))
                .build();

        TrainingException e = assertThrows(TrainingException.class, () -> runner.run(spec));

        assertTrue(e.getMessage().contains("did not exit"), e.getMessage());
    }

    @Test
    void anUnstartableJavaIsReported() {
        var spec = TrainingSpec.builder(dir.resolve("no-java"), auto, archive())
                .appArgs("-version")
                .build();

        TrainingException e = assertThrows(TrainingException.class, () -> runner.run(spec));

        assertTrue(e.getMessage().contains("Cannot start"), e.getMessage());
    }

    @Test
    void buildsTheCommandLineInOrder() {
        Path archive = dir.resolve("a.jsa");
        var spec = TrainingSpec.builder(Path.of("/jdk/bin/java"), CacheStrategy.APP_CDS, archive)
                .jvmArg("-Xmx256m")
                .jvmArg("-Dfoo=bar")
                .appArgs("-jar", "app.jar", "--flag")
                .build();

        assertEquals(
                List.of(
                        "/jdk/bin/java",
                        "-XX:ArchiveClassesAtExit=" + archive.toAbsolutePath(),
                        "-Xmx256m",
                        "-Dfoo=bar",
                        "-jar",
                        "app.jar",
                        "--flag"),
                runner.command(spec));
    }

    @Test
    void specValidatesItsInputs() {
        var builder = TrainingSpec.builder(Path.of("java"), auto, dir.resolve("a"));
        assertThrows(IllegalArgumentException.class, builder::build); // no app to run
        assertThrows(IllegalArgumentException.class, () -> builder.appArgs("-jar", "a.jar").timeout(Duration.ZERO).build());
    }
}
