package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Prepares an application JAR for a training run.
 *
 * <p>CDS and AOT archives cannot reference classes inside nested JARs, so a Spring Boot fat JAR
 * must be unpacked first ({@code java -Djarmode=tools -jar app.jar extract}, Spring Boot 3.3+).
 * Any other executable JAR (shaded, Quarkus uber-jar, ...) is used as it is.
 */
public final class JarExtractor {

    public enum Kind {
        /** A Spring Boot fat JAR that was extracted into a JAR with a {@code lib/} directory. */
        SPRING_BOOT,
        /** A regular executable JAR, used unchanged. */
        EXECUTABLE_JAR
    }

    /**
     * @param kind        what the input was
     * @param runnableJar the JAR to launch with {@code java -jar}; for Spring Boot this is the
     *                    extracted JAR whose manifest points at the sibling {@code lib/} directory
     */
    public record ExtractedApp(Kind kind, Path runnableJar) {}

    private static final String SPRING_LOADER_PREFIX = "org.springframework.boot.loader";
    private static final Attributes.Name SPRING_BOOT_VERSION = new Attributes.Name("Spring-Boot-Version");
    private static final Pattern MAJOR_MINOR = Pattern.compile("^(\\d+)\\.(\\d+)");

    private final CommandRunner runner;

    public JarExtractor() {
        this(CommandRunner.system(Duration.ofMinutes(2)));
    }

    JarExtractor(CommandRunner runner) {
        this.runner = runner;
    }

    /** Classifies a JAR by its manifest. */
    public static Kind detect(Path jar) {
        return isSpringBoot(readManifest(jar)) ? Kind.SPRING_BOOT : Kind.EXECUTABLE_JAR;
    }

    /**
     * Makes {@code jar} ready to be trained.
     *
     * @param jdk         the target JDK, used to run the extraction so the output matches the runtime
     * @param destination where a Spring Boot JAR is extracted; must not exist or must be empty.
     *                    Ignored for other JARs.
     * @throws IllegalArgumentException if the input is not a usable JAR or the destination is taken
     * @throws IllegalStateException    if the extraction fails
     */
    public ExtractedApp prepare(Path jar, JdkInfo jdk, Path destination) {
        if (!Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("JAR not found: " + jar);
        }
        Manifest manifest = readManifest(jar);
        if (!isSpringBoot(manifest)) {
            return new ExtractedApp(Kind.EXECUTABLE_JAR, jar.toAbsolutePath());
        }
        requireToolsMode(manifest);
        requireEmpty(destination);

        List<String> command = List.of(
                jdk.javaExecutable().toString(),
                "-Djarmode=tools",
                "-jar",
                jar.toAbsolutePath().toString(),
                "extract",
                "--destination",
                destination.toAbsolutePath().toString());
        CommandRunner.CommandResult result;
        try {
            result = runner.run(command);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run Spring Boot extraction: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Spring Boot extraction", e);
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                    "Spring Boot extraction failed (exit " + result.exitCode() + "); tools mode needs Spring Boot 3.3+:\n"
                            + result.output());
        }
        return new ExtractedApp(Kind.SPRING_BOOT, findExtractedJar(destination));
    }

    /**
     * A Spring Boot FAT JAR: its Main-Class is the Spring loader, or it carries {@code Spring-Boot-Version} and no
     * {@code Class-Path}. The second case matters: a fat JAR keeps its dependencies inside BOOT-INF, so it never has a
     * Class-Path, whereas the JAR that {@code -Djarmode=tools extract} writes keeps {@code Spring-Boot-Version} but gets a
     * Class-Path to its lib/ directory and the application's own Main-Class. That extracted JAR is exactly the layout
     * ADR 0003 requires, so it must be an ordinary executable JAR here (found by the first real Spring Boot example).
     */
    private static boolean isSpringBoot(Manifest manifest) {
        return manifest != null
                && (launcher(manifest)
                        || (manifestVersion(manifest) != null
                                && manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH) == null));
    }

    private static String manifestVersion(Manifest manifest) {
        return manifest.getMainAttributes().getValue(SPRING_BOOT_VERSION);
    }

    private static boolean launcher(Manifest manifest) {
        String main = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
        return main != null && main.startsWith(SPRING_LOADER_PREFIX);
    }

    private static void requireToolsMode(Manifest manifest) {
        String version = manifestVersion(manifest);
        if (version == null) {
            return;
        }
        Matcher m = MAJOR_MINOR.matcher(version);
        if (m.find()) {
            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            if (major < 3 || (major == 3 && minor < 3)) {
                throw new IllegalArgumentException(
                        "Spring Boot " + version + " does not support '-Djarmode=tools extract'; 3.3 or newer is required");
            }
        }
    }

    private static Manifest readManifest(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getManifest();
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a valid JAR file: " + jar, e);
        }
    }

    private static void requireEmpty(Path destination) {
        if (!Files.exists(destination)) {
            return;
        }
        try (Stream<Path> entries = Files.isDirectory(destination) ? Files.list(destination) : Stream.of(destination)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException(
                        "Extraction directory already exists and is not empty: " + destination);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect " + destination, e);
        }
    }

    private static Path findExtractedJar(Path destination) {
        try (Stream<Path> entries = Files.list(destination)) {
            List<Path> jars = entries
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar"))
                    .toList();
            if (jars.size() != 1) {
                throw new IllegalStateException(
                        "Expected exactly one JAR in " + destination + " after extraction, found " + jars.size());
            }
            return jars.get(0);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read extraction output in " + destination, e);
        }
    }
}
