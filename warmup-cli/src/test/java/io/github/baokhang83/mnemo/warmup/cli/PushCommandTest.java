package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warmup.core.ArchiveManifest;
import io.github.baokhang83.mnemo.warmup.core.CacheStrategy;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PushCommandTest {

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

    private void writeBuildOutput() throws Exception {
        Path archive = dir.resolve("app.jsa");
        Files.writeString(archive, "archive");
        new ArchiveManifest(
                        ArchiveManifest.SCHEMA_VERSION,
                        CacheStrategy.APP_CDS,
                        new ArchiveManifest.Jdk("Eclipse Adoptium", "21.0.5", "21.0.5+11", 21),
                        null,
                        List.of(),
                        List.of(new ArchiveManifest.ClasspathEntry("app.jar", 1, "ab".repeat(32), Instant.EPOCH)),
                        null)
                .withArchive(archive)
                .write(dir.resolve(ArchiveManifest.FILE_NAME));
    }

    @Test
    void withoutBuildOutputItIsAUsageError() {
        var r = run("push", "--work-dir", dir.toString(), "--store", "oci://127.0.0.1:1/x/y", "--plain-http");
        assertEquals(2, r.exit());
        assertTrue(r.err().contains("run build first"), r.err());
    }

    @Test
    void aMalformedStoreIsAUsageError() throws Exception {
        writeBuildOutput();
        var r = run("push", "--work-dir", dir.toString(), "--store", "registry.example.com/x");
        assertEquals(2, r.exit());
        assertTrue(r.err().contains("oci://"), r.err());
    }

    @Test
    void anUnreachableRegistryExitsOneWithAReason() throws Exception {
        writeBuildOutput();
        var r = run("push", "--work-dir", dir.toString(), "--store", "oci://127.0.0.1:1/x/y", "--plain-http", "--timeout-seconds", "2");
        assertEquals(1, r.exit());
        assertTrue(r.err().contains("Cannot reach registry"), r.err());
    }
}
