package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void preparesAnExecutableJar(@TempDir Path dir) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.acme.Main");
        Path jar = dir.resolve("app.jar");
        try (var out = Files.newOutputStream(jar);
                var jos = new JarOutputStream(out, manifest)) {
            // manifest only
        }

        Result r = run("build", "--java-home", HOME, "--jar", jar.toString(), "--work-dir", dir.resolve("w").toString());

        assertEquals(0, r.exit());
        assertTrue(r.out().contains("Application: EXECUTABLE_JAR"), r.out());
        assertTrue(r.out().contains(jar.toString()), r.out());
    }

    @Test
    void missingJarIsReported(@TempDir Path dir) {
        Result r = run("build", "--java-home", HOME, "--jar", dir.resolve("nope.jar").toString());
        assertEquals(2, r.exit());
        assertTrue(r.err().contains("JAR not found"), r.err());
    }

    @Test
    void badJavaHomeIsReported(@TempDir Path empty) {
        Result r = run("build", "--java-home", empty.toString());
        assertEquals(2, r.exit());
        assertTrue(r.err().contains("No java executable"), r.err());
    }
}
