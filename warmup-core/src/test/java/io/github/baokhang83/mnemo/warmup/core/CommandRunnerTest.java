package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandRunnerTest {

    private static final String JAVA = JdkInfo.current().javaExecutable().toString();

    @Test
    void capturesOutputAndExitCode() throws Exception {
        var result = CommandRunner.system(Duration.ofSeconds(30)).run(List.of(JAVA, "-version"));
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("version"), result.output());
    }

    @Test
    void reportsNonZeroExit() throws Exception {
        var result = CommandRunner.system(Duration.ofSeconds(30)).run(List.of(JAVA, "--no-such-option"));
        assertTrue(result.exitCode() != 0);
    }

    @Test
    void failsWhenTheCommandDoesNotExist() {
        assertThrows(IOException.class, () -> CommandRunner.system(Duration.ofSeconds(5))
                .run(List.of("/definitely/not/a/binary")));
    }

    @Test
    void timesOutLongRunningCommands(@TempDir Path dir) throws IOException {
        Path sleeper = Files.writeString(
                dir.resolve("Sleeper.java"),
                "public class Sleeper { public static void main(String[] a) throws Exception { Thread.sleep(60_000); } }");
        long start = System.nanoTime();

        IOException e = assertThrows(IOException.class, () -> CommandRunner.system(Duration.ofMillis(500))
                .run(List.of(JAVA, sleeper.toString())));

        assertTrue(e.getMessage().startsWith("Timed out"), e.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 30, "should not wait for the process");
    }
}
