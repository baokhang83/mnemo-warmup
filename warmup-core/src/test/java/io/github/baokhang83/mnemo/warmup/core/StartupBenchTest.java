package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warmup.core.JarExtractor.ExtractedApp;
import io.github.baokhang83.mnemo.warmup.core.JarExtractor.Kind;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupBenchTest {

    @TempDir
    Path dir;

    private final JdkInfo jdk = JdkInfo.current();
    private final CacheStrategy strategy = CacheType.AUTO.resolve(jdk);

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** A probe with no warmup traffic, which is what a benchmark must use. */
    private static ExitMode probe(int port) {
        return new HttpProbeExitMode(new HttpProbeExitMode.Settings(
                URI.create("http://127.0.0.1:" + port + "/health"), Duration.ofMillis(20), List.of(), null));
    }

    @Test
    void commandAddsTheRuntimeFlagOnlyForArchiveVariants() throws IOException {
        var app = new ExtractedApp(Kind.EXECUTABLE_JAR, TestApps.jar(dir, "hello", TestApps.HELLO));
        ExitMode mode = new SpringRefreshExitMode();
        var bench = new StartupBench();

        List<String> none = bench.command(jdk, StartupBench.Variant.none(), app, mode, List.of("-Xmx64m"));
        List<String> cached = bench.command(jdk, StartupBench.Variant.of("cached", strategy, dir.resolve("a.x")), app, mode, List.of("-Xmx64m"));

        assertEquals(List.of(jdk.javaExecutable().toString(), "-Xmx64m", "-Dspring.context.exit=onRefresh", "-jar",
                app.runnableJar().toString()), none);
        assertEquals(strategy.runtimeFlag(dir.resolve("a.x").toAbsolutePath().toString()), cached.get(1));
    }

    @Test
    void measuresRunsAndDropsTheWarmups() throws IOException {
        int port = freePort();
        var app = new ExtractedApp(Kind.EXECUTABLE_JAR, TestApps.jar(dir, "server", TestApps.HTTP_SERVER));

        var samples = new StartupBench().measure(jdk, StartupBench.Variant.none(), app, probe(port),
                List.of("-Dport=" + port), Duration.ofSeconds(60), 1, 2, dir.resolve("bench.log"));

        assertEquals(2, samples.size());
        for (var sample : samples) {
            // TestApps.HTTP_SERVER answers 503 for its first second, so "started" cannot come earlier.
            assertTrue(sample.startup().toMillis() >= 1000, sample.toString());
            assertTrue(sample.startup().toSeconds() < 30, sample.toString());
        }
    }

    @Test
    void anApplicationThatCrashesIsReportedWithItsLog() throws IOException {
        var app = new ExtractedApp(Kind.EXECUTABLE_JAR, TestApps.jar(dir, "crash", TestApps.CRASH));

        TrainingException e = assertThrows(TrainingException.class, () -> new StartupBench().measure(
                jdk, StartupBench.Variant.none(), app, new SpringRefreshExitMode(), List.of(),
                Duration.ofSeconds(60), 0, 1, dir.resolve("crash.log")));

        assertTrue(e.getMessage().contains("code 3"), e.getMessage());
    }

    @Test
    void runCountsAreChecked() throws IOException {
        var app = new ExtractedApp(Kind.EXECUTABLE_JAR, TestApps.jar(dir, "hello", TestApps.HELLO));
        assertThrows(IllegalArgumentException.class, () -> new StartupBench().measure(
                jdk, StartupBench.Variant.none(), app, new SpringRefreshExitMode(), List.of(), Duration.ofSeconds(5), 0, 0, dir.resolve("x.log")));
    }
}
