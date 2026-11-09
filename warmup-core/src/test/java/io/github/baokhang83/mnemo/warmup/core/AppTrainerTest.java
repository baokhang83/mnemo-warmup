package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warmup.core.JarExtractor.ExtractedApp;
import io.github.baokhang83.mnemo.warmup.core.JarExtractor.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppTrainerTest {

    @TempDir
    Path dir;

    private final JdkInfo jdk = JdkInfo.current();
    private final CacheStrategy strategy = CacheType.AUTO.resolve(jdk);

    @Test
    void springRefreshPassesTheExitPropertyAndTrains() throws IOException {
        Path jar = TestApps.jar(dir, "spring-like", TestApps.EXITS_ON_PROPERTY);
        Path archive = dir.resolve("app." + strategy.extension());

        var result = new AppTrainer().train(
                new ExtractedApp(Kind.SPRING_BOOT, jar), jdk, strategy, new SpringRefreshExitMode(), archive,
                Duration.ofSeconds(60));

        assertFalse(result.stoppedByUs(), "the app should have exited by itself");
        assertTrue(Files.size(archive) > 0);
        assertTrue(Files.readString(result.logFile()).contains("refreshed"));
    }

    @Test
    void anExecutableJarIsRejectedBeforeAnythingRuns() throws IOException {
        Path jar = TestApps.jar(dir, "plain", TestApps.HELLO);
        Path archive = dir.resolve("app." + strategy.extension());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new AppTrainer().train(
                new ExtractedApp(Kind.EXECUTABLE_JAR, jar), jdk, strategy, new SpringRefreshExitMode(), archive,
                Duration.ofSeconds(60)));

        assertTrue(e.getMessage().contains("spring-refresh"), e.getMessage());
        assertFalse(Files.exists(archive));
    }

    @Test
    void exitModeNamesResolve() {
        assertEquals("spring-refresh", ExitMode.parse(" Spring-Refresh ").name());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ExitMode.parse("magic"));
        assertTrue(e.getMessage().contains("spring-refresh"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ExitMode.parse(null));
    }

    @Test
    void springRefreshDescribesItself() {
        ExitMode mode = new SpringRefreshExitMode();
        assertEquals(List.of("-Dspring.context.exit=onRefresh"), mode.jvmArgs());
        assertTrue(mode.supports(Kind.SPRING_BOOT));
        assertFalse(mode.supports(Kind.EXECUTABLE_JAR));
    }
}
