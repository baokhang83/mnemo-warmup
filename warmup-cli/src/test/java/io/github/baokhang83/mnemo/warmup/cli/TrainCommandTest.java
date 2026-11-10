package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.baokhang83.mnemo.warmup.core.ArchiveFetcher;
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

class TrainCommandTest {

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

    private Path serverJar() throws Exception {
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

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void usageErrorsExitTwo() {
        assertEquals(2, run("train", "--store", "oci://r/x").exit(), "needs --jar");
        assertEquals(2, run("train", "--jar", "x.jar").exit(), "needs --store");
        Result badLock = run("train", "--java-home", HOME, "--jar", "x.jar", "--store", "oci://r/x", "--lock", "magic");
        assertEquals(2, badLock.exit());
        assertTrue(badLock.err().contains("--lock"), badLock.err());
    }

    @Test
    void aSpringBootFatJarIsRefusedWithTheExtractionHint() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Spring-Boot-Version", "3.4.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "org.springframework.boot.loader.launch.JarLauncher");
        Path jar = dir.resolve("fat.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream ignored = new JarOutputStream(out, manifest)) {
            // contents do not matter
        }
        Result r = run("train", "--java-home", HOME, "--jar", jar.toString(), "--store", "oci://127.0.0.1:1/x/y", "--plain-http", "--lock", "none");
        assertEquals(2, r.exit());
        assertTrue(r.err().contains("extract the Spring Boot JAR at image build time"), r.err());
    }

    @Test
    void anUnreachableStoreIsAFailedOutcomeWithExitZeroAndOneWhenStrict() throws Exception {
        String[] args = {
            "train", "--java-home", HOME, "--jar", serverJar().toString(), "--work-dir", dir.resolve("w").toString(),
            "--exit-mode", "http-probe", "--url", "http://127.0.0.1:1/health", "--store", "oci://127.0.0.1:1/x/y", "--plain-http",
            "--store-timeout-seconds", "2", "--lock", "none"
        };
        Result r = run(args);
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.err().contains("FAILED") && r.err().contains("cannot check the store"), r.err());

        String[] strict = java.util.Arrays.copyOf(args, args.length + 1);
        strict[args.length] = "--strict";
        assertEquals(1, run(strict).exit());
    }

    @Test
    void outsideAClusterTheLeaseLockIsAFailedOutcomeNotACrash() throws Exception {
        assumeTrue(System.getenv("KUBERNETES_SERVICE_HOST") == null, "only meaningful outside a cluster");
        Result r = run("train", "--java-home", HOME, "--jar", serverJar().toString(), "--work-dir", dir.resolve("w").toString(),
                "--exit-mode", "http-probe", "--url", "http://127.0.0.1:1/health", "--store", "oci://127.0.0.1:1/x/y", "--plain-http");
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.err().contains("FAILED") && r.err().contains("Not running in a Kubernetes pod"), r.err());
    }

    /** Needs {@code -Dwarmup.registry=host:port} (plain-HTTP, anonymous): trains for real, uploads, and a second run does nothing. */
    @Test
    void trainsOnceUploadsAndTheSecondRunFindsItStored() throws Exception {
        String registry = System.getProperty("warmup.registry");
        assumeTrue(registry != null && !registry.isBlank(), "set -Dwarmup.registry=host:port to run");
        Path jar = serverJar();
        int port = freePort();
        String store = "oci://" + registry + "/mnemo/train-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String[] args = {
            "train", "--java-home", HOME, "--jar", jar.toString(), "--work-dir", dir.resolve("w").toString(),
            "--exit-mode", "http-probe", "--url", "http://127.0.0.1:" + port + "/health", "--jvm-arg=-Dport=" + port,
            "--store", store, "--plain-http", "--lock", "none", "--strict"
        };

        Result first = run(args);
        assertEquals(0, first.exit(), first.err() + first.out());
        assertTrue(first.out().contains("TRAINED"), first.out());

        Result second = run(args);
        assertEquals(0, second.exit(), second.err());
        assertTrue(second.out().contains("ALREADY_STORED"), second.out());
        assertTrue(!second.out().contains("Training"), "the second run must not train again: " + second.out());

        // and a pod's fetch with the same launch finds what the trainer stored
        Path cache = dir.resolve("cache");
        Result fetch = run("fetch", "--java-home", HOME, "--jar", jar.toString(), "--work-dir", dir.resolve("w2").toString(),
                "--jvm-arg=-Dport=" + port, "--store", store, "--plain-http", "--out", cache.toString(), "--strict");
        assertEquals(0, fetch.exit(), fetch.err());
        assertTrue(Files.isRegularFile(cache.resolve(ArchiveFetcher.FLAGS_FILE)), "fetch must find the trained archive");
    }
}
