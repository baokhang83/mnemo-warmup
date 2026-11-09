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

    /** Behaves like Spring with spring.context.exit=onRefresh: exits by itself only if told to. */
    static final String EXITS_ON_PROPERTY =
            "public class Main { public static void main(String[] a) throws Exception {"
                    + " if (\"onRefresh\".equals(System.getProperty(\"spring.context.exit\"))) {"
                    + " System.out.println(\"refreshed\"); return; }"
                    + " System.out.println(\"waiting\"); Thread.sleep(600_000); } }";

    /** An HTTP server on -Dport: /health is 503 for the first second, then 200; /warm logs each call. */
    static final String HTTP_SERVER = """
            import com.sun.net.httpserver.HttpServer;
            import java.net.InetSocketAddress;
            public class Main {
                public static void main(String[] a) throws Exception {
                    int port = Integer.getInteger("port");
                    long readyAt = System.currentTimeMillis() + 1000;
                    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
                    server.createContext("/health", ex -> {
                        ex.sendResponseHeaders(System.currentTimeMillis() >= readyAt ? 200 : 503, -1);
                        ex.close();
                    });
                    server.createContext("/warm", ex -> {
                        System.out.println("WARM " + ex.getRequestURI());
                        System.out.flush();
                        ex.sendResponseHeaders(200, -1);
                        ex.close();
                    });
                    server.start();
                    System.out.println("listening");
                }
            }
            """;

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
