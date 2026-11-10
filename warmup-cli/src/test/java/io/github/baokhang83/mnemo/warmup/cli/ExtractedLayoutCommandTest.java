package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warmup.core.JarExtractor;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The layout the tool recommends for Spring Boot (extracted at image build time) must work with every command that takes
 * a JAR, including the default spring-refresh mode. The manifest has the shape {@code -Djarmode=tools extract} really
 * writes (checked on Spring Boot 3.5.16): the version attribute, the application's own Main-Class, a Class-Path to lib/.
 */
class ExtractedLayoutCommandTest {

    private static final String HOME = System.getProperty("java.home");

    private static final String APP = """
            public class Main {
                public static void main(String[] a) throws Exception {
                    if ("onRefresh".equals(System.getProperty("spring.context.exit"))) { System.out.println("refreshed"); return; }
                    System.out.println("waiting");
                    Thread.sleep(600_000);
                }
            }
            """;

    @TempDir
    Path dir;

    private record Result(int exit, String out, String err) {}

    private static Result run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        var cmd = Main.commandLine();
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        return new Result(cmd.execute(args), out.toString(), err.toString());
    }

    /** {@code <dir>/extracted/app.jar} plus {@code lib/dependency.jar}, like the extracted example. */
    private Path extractedApp() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Files.writeString(src.resolve("Main.java"), APP);
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), src.resolve("Main.java").toString()) != 0) {
            throw new IllegalStateException("cannot compile the test app");
        }
        Path root = Files.createDirectories(dir.resolve("extracted"));
        Path lib = Files.createDirectories(root.resolve("lib"));
        Manifest empty = new Manifest();
        empty.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(lib.resolve("dependency.jar")); JarOutputStream ignored = new JarOutputStream(out, empty)) {
            // a dependency with no content
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Main");
        manifest.getMainAttributes().putValue("Spring-Boot-Version", "3.5.16");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "lib/dependency.jar");
        Path jar = root.resolve("app.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out, manifest)) {
            jos.putNextEntry(new JarEntry("Main.class"));
            jos.write(Files.readAllBytes(classes.resolve("Main.class")));
            jos.closeEntry();
        }
        return jar;
    }

    @Test
    void theFixtureIsASpringBootAppThatNeedsNoExtraction() throws Exception {
        Path jar = extractedApp();
        assertEquals(JarExtractor.Kind.SPRING_BOOT, JarExtractor.detect(jar));
        assertTrue(!JarExtractor.needsExtraction(jar));
    }

    @Test
    void buildTrainsTheExtractedLayoutWithTheDefaultSpringRefreshMode() throws Exception {
        Path jar = extractedApp();
        Result r = run("build", "--java-home", HOME, "--jar", jar.toString(), "--work-dir", dir.resolve("w").toString(), "--timeout-seconds", "120");
        assertEquals(0, r.exit(), r.err() + r.out());
        assertTrue(r.out().contains("Fingerprint: sha256:"), r.out());
        try (var files = Files.list(dir.resolve("w"))) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().matches("app\\.(jsa|aot)")), "an archive must exist");
        }
    }

    @Test
    void benchMeasuresTheExtractedLayoutWithTheDefaultSpringRefreshMode() throws Exception {
        Path jar = extractedApp();
        Result r = run("bench", "--java-home", HOME, "--jar", jar.toString(), "--work-dir", dir.resolve("w").toString(),
                "--runs", "1", "--warmups", "0", "--timeout-seconds", "120");
        assertEquals(0, r.exit(), r.err() + r.out());
        assertTrue(r.out().contains("| Variant"), r.out());
    }

    @Test
    void trainAcceptsTheExtractedLayoutWithTheDefaultMode() throws Exception {
        Path jar = extractedApp();
        Result r = run("train", "--java-home", HOME, "--jar", jar.toString(), "--work-dir", dir.resolve("w").toString(),
                "--store", "oci://127.0.0.1:1/x/y", "--plain-http", "--store-timeout-seconds", "2", "--lock", "none");
        // it gets as far as the (unreachable) store, which proves the JAR itself was accepted
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.err().contains("cannot check the store"), r.err());
        assertTrue(!r.err().contains("extract the Spring Boot JAR"), r.err());
    }
}
