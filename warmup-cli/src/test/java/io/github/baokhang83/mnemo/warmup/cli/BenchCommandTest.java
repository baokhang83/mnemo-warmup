package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchCommandTest {

    private static final int FEATURE = Runtime.version().feature();
    private static final String HOME = System.getProperty("java.home");

    private static final String SERVER = """
            import com.sun.net.httpserver.HttpServer;
            import java.net.InetSocketAddress;
            public class Main {
                public static void main(String[] a) throws Exception {
                    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.getInteger("port")), 0);
                    s.createContext("/health", ex -> { ex.sendResponseHeaders(200, -1); ex.close(); });
                    s.start();
                }
            }
            """;

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

    private static Path serverJar(Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Files.writeString(src.resolve("Main.java"), SERVER);
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), src.resolve("Main.java").toString()) != 0) {
            throw new IllegalStateException("cannot compile test server");
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Main");
        Path jar = dir.resolve("server.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out, manifest)) {
            jos.putNextEntry(new JarEntry("Main.class"));
            jos.write(Files.readAllBytes(classes.resolve("Main.class")));
            jos.closeEntry();
        }
        return jar;
    }

    @Test
    void needsAJar() {
        Result r = run("bench", "--java-home", HOME);
        assertEquals(2, r.exit());
        assertTrue(r.err().contains("needs --jar"), r.err());
    }

    @Test
    void rejectsZeroRuns(@TempDir Path dir) throws Exception {
        Result r = run("bench", "--java-home", HOME, "--jar", serverJar(dir).toString(), "--runs", "0");
        assertEquals(2, r.exit());
    }

    @Test
    void printsATableWithOneRowPerVariant(@TempDir Path dir) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        Result r = run("bench", "--java-home", HOME, "--jar", serverJar(dir).toString(),
                "--work-dir", dir.resolve("w").toString(), "--exit-mode", "http-probe",
                "--url", "http://127.0.0.1:" + port + "/health", "--jvm-arg=-Dport=" + port,
                "--runs", "2", "--warmups", "0", "--timeout-seconds", "60");

        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("| Variant | Startup median (ms)"), r.out());
        assertTrue(r.out().contains("| none |"), r.out());
        assertTrue(r.out().contains("| appcds |"), r.out());
        assertEquals(FEATURE >= 25, r.out().contains("| aot |"), r.out());
        assertFalse(r.out().contains("Saved vs appcds"), "the baseline is the first row, none");
    }
}
