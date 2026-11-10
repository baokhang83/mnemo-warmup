package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warmup.core.JarExtractor.ExtractedApp;
import io.github.baokhang83.mnemo.warmup.core.JarExtractor.Kind;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarExtractorTest {

    @TempDir
    Path dir;

    private final JdkInfo jdk = JdkInfo.current();

    private Path jar(String name, String... manifestEntries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        for (int i = 0; i < manifestEntries.length; i += 2) {
            manifest.getMainAttributes().putValue(manifestEntries[i], manifestEntries[i + 1]);
        }
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
                JarOutputStream jos = new JarOutputStream(out, manifest)) {
            // manifest only
        }
        return file;
    }

    /** Pretends to be `java -Djarmode=tools ... extract --destination <dir>`. */
    private static final class FakeTools implements CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        int exitCode = 0;
        String output = "";
        boolean createJar = true;

        @Override
        public CommandResult run(List<String> command) throws IOException {
            commands.add(command);
            if (exitCode == 0) {
                Path dest = Path.of(command.get(command.indexOf("--destination") + 1));
                Files.createDirectories(dest.resolve("lib"));
                Files.writeString(dest.resolve("lib/dep.jar"), "x");
                if (createJar) {
                    Files.writeString(dest.resolve("app.jar"), "x");
                }
            }
            return new CommandResult(exitCode, output);
        }
    }

    @Test
    void detectsSpringBootByVersionAttribute() throws IOException {
        assertEquals(Kind.SPRING_BOOT, JarExtractor.detect(jar("a.jar", "Spring-Boot-Version", "3.4.1")));
    }

    @Test
    void detectsSpringBootByLauncher() throws IOException {
        Path j = jar("b.jar", "Main-Class", "org.springframework.boot.loader.launch.JarLauncher");
        assertEquals(Kind.SPRING_BOOT, JarExtractor.detect(j));
    }

    @Test
    void theJarThatToolsExtractWritesIsAnExecutableJarNotAFatJar() throws IOException {
        // what `java -Djarmode=tools -jar app.jar extract` really writes (checked on Spring Boot 3.5): the version attribute
        // survives, the loader is gone, the application's Main-Class and a Class-Path to lib/ are added
        Path extracted = jar("extracted.jar",
                "Spring-Boot-Version", "3.5.16",
                "Main-Class", "com.acme.DemoApplication",
                "Class-Path", "lib/spring-boot-3.5.16.jar lib/spring-core-6.2.19.jar");
        assertEquals(Kind.EXECUTABLE_JAR, JarExtractor.detect(extracted));

        var app = new JarExtractor((cmd) -> { throw new AssertionError("an extracted JAR must not be extracted again"); })
                .prepare(extracted, JdkInfo.current(), dir.resolve("never"));
        assertEquals(Kind.EXECUTABLE_JAR, app.kind());
        assertEquals(extracted.toAbsolutePath(), app.runnableJar());
    }

    @Test
    void aFatJarIsRecognisedEvenWithAClassPathLessManifestOrTheLoaderAsMain() throws IOException {
        assertEquals(Kind.SPRING_BOOT, JarExtractor.detect(jar("fat1.jar", "Spring-Boot-Version", "3.5.16", "Start-Class", "com.acme.DemoApplication")));
        assertEquals(Kind.SPRING_BOOT, JarExtractor.detect(jar("fat2.jar",
                "Main-Class", "org.springframework.boot.loader.launch.JarLauncher", "Class-Path", "ignored")));
    }

    @Test
    void otherJarsAreExecutableJars() throws IOException {
        assertEquals(Kind.EXECUTABLE_JAR, JarExtractor.detect(jar("c.jar", "Main-Class", "com.acme.Main")));
        assertEquals(Kind.EXECUTABLE_JAR, JarExtractor.detect(jar("d.jar")));
    }

    @Test
    void springBootJarIsExtractedWithTargetJdk() throws IOException {
        Path input = jar("app.jar", "Spring-Boot-Version", "3.4.1");
        Path dest = dir.resolve("out");
        FakeTools tools = new FakeTools();

        ExtractedApp app = new JarExtractor(tools).prepare(input, jdk, dest);

        assertEquals(Kind.SPRING_BOOT, app.kind());
        assertEquals(dest.resolve("app.jar"), app.runnableJar());
        List<String> cmd = tools.commands.get(0);
        assertEquals(jdk.javaExecutable().toString(), cmd.get(0));
        assertEquals(List.of("-Djarmode=tools", "-jar"), cmd.subList(1, 3));
        assertEquals(input.toAbsolutePath().toString(), cmd.get(3));
        assertEquals("extract", cmd.get(4));
        assertEquals(dest.toAbsolutePath().toString(), cmd.get(6));
    }

    @Test
    void executableJarIsUsedAsIsWithoutRunningAnything() throws IOException {
        Path input = jar("shaded.jar", "Main-Class", "com.acme.Main");
        FakeTools tools = new FakeTools();

        ExtractedApp app = new JarExtractor(tools).prepare(input, jdk, dir.resolve("out"));

        assertEquals(Kind.EXECUTABLE_JAR, app.kind());
        assertEquals(input.toAbsolutePath(), app.runnableJar());
        assertTrue(tools.commands.isEmpty());
        assertTrue(Files.notExists(dir.resolve("out")));
    }

    @Test
    void failedExtractionReportsOutput() throws IOException {
        FakeTools tools = new FakeTools();
        tools.exitCode = 1;
        tools.output = "Unknown jarmode 'tools'";
        Path input = jar("app.jar", "Main-Class", "org.springframework.boot.loader.launch.JarLauncher");

        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> new JarExtractor(tools).prepare(input, jdk, dir.resolve("out")));
        assertTrue(e.getMessage().contains("Unknown jarmode"), e.getMessage());
        assertTrue(e.getMessage().contains("3.3"), e.getMessage());
    }

    @Test
    void oldSpringBootIsRejectedBeforeRunningAnything() throws IOException {
        FakeTools tools = new FakeTools();
        for (String old : List.of("3.2.5", "2.7.18")) {
            Path input = jar("old-" + old + ".jar", "Spring-Boot-Version", old);
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> new JarExtractor(tools).prepare(input, jdk, dir.resolve("out")));
            assertTrue(e.getMessage().contains("3.3"), e.getMessage());
        }
        assertTrue(tools.commands.isEmpty());
    }

    @Test
    void springBoot3_3AndFutureMajorsAreAccepted() throws IOException {
        for (String version : List.of("3.3.0", "4.0.0")) {
            Path input = jar("v-" + version + ".jar", "Spring-Boot-Version", version);
            ExtractedApp app = new JarExtractor(new FakeTools()).prepare(input, jdk, dir.resolve("out-" + version));
            assertEquals(Kind.SPRING_BOOT, app.kind());
        }
    }

    @Test
    void refusesANonEmptyDestination() throws IOException {
        Path input = jar("app.jar", "Spring-Boot-Version", "3.4.1");
        Path dest = Files.createDirectory(dir.resolve("out"));
        Files.writeString(dest.resolve("leftover"), "x");
        FakeTools tools = new FakeTools();

        assertThrows(IllegalArgumentException.class, () -> new JarExtractor(tools).prepare(input, jdk, dest));
        assertTrue(tools.commands.isEmpty());
    }

    @Test
    void anEmptyDestinationIsFine() throws IOException {
        Path input = jar("app.jar", "Spring-Boot-Version", "3.4.1");
        Path dest = Files.createDirectory(dir.resolve("out"));
        assertEquals(Kind.SPRING_BOOT, new JarExtractor(new FakeTools()).prepare(input, jdk, dest).kind());
    }

    @Test
    void missingExtractedJarIsAnError() throws IOException {
        FakeTools tools = new FakeTools();
        tools.createJar = false;
        Path input = jar("app.jar", "Spring-Boot-Version", "3.4.1");
        assertThrows(IllegalStateException.class, () -> new JarExtractor(tools).prepare(input, jdk, dir.resolve("out")));
    }

    @Test
    void rejectsMissingAndInvalidJars() throws IOException {
        JarExtractor extractor = new JarExtractor(new FakeTools());
        assertThrows(IllegalArgumentException.class, () -> extractor.prepare(dir.resolve("nope.jar"), jdk, dir));
        Path notAJar = Files.writeString(dir.resolve("text.jar"), "not a zip");
        assertThrows(IllegalArgumentException.class, () -> extractor.prepare(notAJar, jdk, dir.resolve("out")));
    }
}
