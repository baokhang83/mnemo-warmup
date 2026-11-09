package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class BuildCommandTest {

    private static final int FEATURE = Runtime.version().feature();
    private static final String HOME = System.getProperty("java.home");

    private record Result(int exit, String out, String err) {}

    private static Result run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        var cmd = Main.commandLine();
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        int exit = cmd.execute(args);
        return new Result(exit, out.toString(), err.toString());
    }

    @Test
    void autoPicksStrategyForTargetJdk() {
        Result r = run("build", "--java-home", HOME);
        assertEquals(0, r.exit());
        String expected = FEATURE >= 25 ? "LEYDEN_AOT" : "APP_CDS";
        assertTrue(r.out().contains("Cache: " + expected), r.out());
        assertTrue(r.out().contains("feature " + FEATURE), r.out());
    }

    @Test
    void appCdsCanAlwaysBeForced() {
        Result r = run("build", "--java-home", HOME, "--cache-type", "appcds");
        assertEquals(0, r.exit());
        assertTrue(r.out().contains("Cache: APP_CDS"), r.out());
    }

    @Test
    void aotIsRejectedOnOldJdks() {
        Result r = run("build", "--java-home", HOME, "--cache-type", "aot");
        if (FEATURE >= 25) {
            assertEquals(0, r.exit());
        } else {
            assertEquals(2, r.exit());
            assertTrue(r.err().contains("JDK 25"), r.err());
        }
    }

    @Test
    void unknownCacheTypeIsAUsageError() {
        Result r = run("build", "--java-home", HOME, "--cache-type", "cds");
        assertEquals(2, r.exit());
        assertTrue(r.err().contains("Unknown cache type"), r.err());
    }

    @Test
    void badJavaHomeIsReported(@org.junit.jupiter.api.io.TempDir java.nio.file.Path empty) {
        Result r = run("build", "--java-home", empty.toString());
        assertEquals(2, r.exit());
        assertTrue(r.err().contains("No java executable"), r.err());
    }
}
