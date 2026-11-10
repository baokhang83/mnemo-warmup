package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.baokhang83.mnemo.warmup.core.ArchiveFetcher;
import io.github.baokhang83.mnemo.warmup.core.ArchiveManifest;
import io.github.baokhang83.mnemo.warmup.core.CacheStrategy;
import io.github.baokhang83.mnemo.warmup.core.JarExtractor;
import io.github.baokhang83.mnemo.warmup.core.JdkInfo;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FetchCommandTest {

    private static final String HOME = System.getProperty("java.home");

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

    private Path jar() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Main");
        Path jar = dir.resolve("app.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out, manifest)) {
            // no entries needed: fetch only hashes the classpath
        }
        return jar;
    }

    private Path staleFlags(Path out) throws Exception {
        Files.createDirectories(out);
        return Files.writeString(out.resolve(ArchiveFetcher.FLAGS_FILE), "-XX:SharedArchiveFile=/stale.jsa\n");
    }

    @Test
    void missingArgumentsStillExitZeroAndClearStaleFlags() throws Exception {
        Path out = dir.resolve("cache");
        Path flags = staleFlags(out);
        Result r = run("fetch", "--out", out.toString());
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.err().contains("needs --store and --jar"), r.err());
        assertTrue(r.err().contains("continuing without an archive"), r.err());
        assertFalse(Files.exists(flags));
    }

    @Test
    void strictTurnsBadArgumentsIntoExitTwo() {
        Result r = run("fetch", "--strict", "--out", dir.resolve("cache").toString());
        assertEquals(2, r.exit());
    }

    @Test
    void anUnreachableStoreExitsZeroWithoutFlagsAndStrictExitsOne() throws Exception {
        Path out = dir.resolve("cache");
        Path flags = staleFlags(out);
        String[] args = {
            "fetch", "--java-home", HOME, "--jar", jar().toString(), "--work-dir", dir.resolve("w").toString(),
            "--store", "oci://127.0.0.1:1/x/y", "--plain-http", "--store-timeout-seconds", "2", "--out", out.toString()
        };
        Result r = run(args);
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("No usable archive"), r.out());
        assertTrue(r.err().contains("Cannot reach registry"), r.err());
        assertFalse(Files.exists(flags));

        String[] strict = java.util.Arrays.copyOf(args, args.length + 1);
        strict[args.length] = "--strict";
        assertEquals(1, run(strict).exit());
    }

    @Test
    void aMalformedStoreExitsZero() throws Exception {
        Result r = run("fetch", "--java-home", HOME, "--jar", jar().toString(), "--work-dir", dir.resolve("w").toString(),
                "--store", "registry.example.com/x", "--out", dir.resolve("cache").toString());
        assertEquals(0, r.exit());
        assertTrue(r.err().contains("oci://"), r.err());
    }

    /** Needs {@code -Dwarmup.registry=host:port} (plain-HTTP, anonymous), e.g. docker run -p 5055:5000 registry:2. */
    @Test
    void pushThenFetchRoundTripsThroughARealRegistry() throws Exception {
        String registry = System.getProperty("warmup.registry");
        assumeTrue(registry != null && !registry.isBlank(), "set -Dwarmup.registry=host:port to run");

        Path jar = jar();
        JdkInfo jdk = JdkInfo.detect(Path.of(HOME), System.getenv());
        CacheStrategy strategy = jdk.featureVersion() >= 25 ? CacheStrategy.LEYDEN_AOT : CacheStrategy.APP_CDS;
        var app = new JarExtractor().prepare(jar, jdk, dir.resolve("unused"));
        String digest = "sha256:" + java.util.UUID.randomUUID().toString().replace("-", "").repeat(2);
        Path work = Files.createDirectories(dir.resolve("work"));
        Path archive = Files.writeString(work.resolve("app." + strategy.extension()), "archive-bytes");
        ArchiveManifest.describe(app, jdk, strategy, List.of(), System.getenv(), digest)
                .withArchive(archive)
                .write(work.resolve(ArchiveManifest.FILE_NAME));

        String store = "oci://" + registry + "/mnemo/cli";
        Result push = run("push", "--work-dir", work.toString(), "--store", store, "--plain-http");
        assertEquals(0, push.exit(), push.err());

        Path out = dir.resolve("cache");
        Result fetch = run("fetch", "--java-home", HOME, "--jar", jar.toString(), "--work-dir", dir.resolve("w2").toString(),
                "--image-digest", digest, "--store", store, "--plain-http", "--out", out.toString(), "--strict");
        assertEquals(0, fetch.exit(), fetch.err());
        String flags = Files.readString(out.resolve(ArchiveFetcher.FLAGS_FILE));
        assertTrue(flags.startsWith(strategy.runtimeFlag("")), flags);
        assertEquals("archive-bytes", Files.readString(out.resolve("app." + strategy.extension())));
    }
}
