package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Compiles tiny throw-away applications into executable JARs for process-level tests. */
final class TestApps {

    private TestApps() {}

    /** Prints a line and exits normally. */
    static final String HELLO =
            "public class Main { public static void main(String[] a) { System.out.println(\"hello\"); } }";

    /** Prints a line, then runs until it is stopped. */
    static final String SLEEPER =
            "public class Main { public static void main(String[] a) throws Exception {"
                    + " System.out.println(\"up\"); Thread.sleep(600_000); } }";

    /** Fails right away. */
    static final String CRASH =
            "public class Main { public static void main(String[] a) {"
                    + " System.out.println(\"boom\"); System.exit(3); } }";

    /** Starts fine but takes a minute to shut down after SIGTERM. */
    static final String STUBBORN =
            "public class Main { public static void main(String[] a) throws Exception {"
                    + " Runtime.getRuntime().addShutdownHook(new Thread(() -> {"
                    + " try { Thread.sleep(60_000); } catch (InterruptedException e) { } }));"
                    + " System.out.println(\"up\"); Thread.sleep(600_000); } }";

    /** Builds {@code <dir>/<name>.jar} with {@code Main-Class: Main}. */
    static Path jar(Path dir, String name, String mainSource) throws IOException {
        Path src = Files.createDirectories(dir.resolve(name + "-src"));
        Path classes = Files.createDirectories(dir.resolve(name + "-classes"));
        Path java = src.resolve("Main.java");
        Files.writeString(java, mainSource);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null || compiler.run(null, null, null, "-d", classes.toString(), java.toString()) != 0) {
            throw new IllegalStateException("Cannot compile test app " + name);
        }

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Main");
        Path jar = dir.resolve(name + ".jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out, manifest)) {
            jos.putNextEntry(new JarEntry("Main.class"));
            jos.write(Files.readAllBytes(classes.resolve("Main.class")));
            jos.closeEntry();
        }
        return jar;
    }
}
