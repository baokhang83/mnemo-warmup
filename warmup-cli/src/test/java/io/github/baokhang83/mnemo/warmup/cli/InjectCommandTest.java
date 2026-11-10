package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.io.PrintWriter;
import java.io.InputStream;
import org.junit.jupiter.api.AfterEach;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class InjectCommandTest {
    private final InputStream originalInput = System.in;
    @AfterEach void restoreInput() { System.setIn(originalInput); }
    @Test void malformedInputEmitsNoManifest() {
        var cmd = Main.commandLine(); var out = new StringWriter(); var err = new StringWriter();
        System.setIn(new java.io.ByteArrayInputStream("kind: [bad\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))); cmd.setOut(new PrintWriter(out, true)); cmd.setErr(new PrintWriter(err, true));
        assertEquals(2, cmd.execute("inject")); assertEquals("", out.toString()); assertTrue(err.toString().contains("inject:"));
    }
    @Test void previewDoesNotEmitManifestStream() {
        var cmd = Main.commandLine(); var out = new StringWriter(); var err = new StringWriter();
        System.setIn(new java.io.ByteArrayInputStream("apiVersion: v1\nkind: Service\nmetadata: {name: app}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))); cmd.setOut(new PrintWriter(out, true)); cmd.setErr(new PrintWriter(err, true));
        assertEquals(0, cmd.execute("inject", "--preview")); assertTrue(out.toString().contains("No opted-in workloads"));
    }
    @Test void aDevelopmentBuildAsksForTheToolsImageAndAcceptsOne() {
        String manifest = "apiVersion: apps/v1\nkind: Deployment\nmetadata: {name: demo, namespace: t}\nspec:\n  template:\n    metadata:\n      annotations:\n        warmup.mnemo.io/enabled: \"true\"\n        warmup.mnemo.io/store: oci://example.com/demo\n        warmup.mnemo.io/exit-mode: spring-refresh\n    spec:\n      containers:\n        - {name: app, image: example/demo:1, command: [java, -jar, /app/app.jar]}\n";
        var cmd = Main.commandLine(); var out = new StringWriter(); var err = new StringWriter();
        cmd.setOut(new PrintWriter(out, true)); cmd.setErr(new PrintWriter(err, true));
        System.setIn(new java.io.ByteArrayInputStream(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(2, cmd.execute("inject")); assertEquals("", out.toString()); assertTrue(err.toString().contains("--tools-image"), err.toString());

        out.getBuffer().setLength(0); err.getBuffer().setLength(0);
        System.setIn(new java.io.ByteArrayInputStream(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(0, cmd.execute("inject", "--tools-image", "registry.example.com/tools:1.2.3"));
        assertTrue(out.toString().contains("registry.example.com/tools:1.2.3"));
        assertTrue(err.toString().contains("tools=registry.example.com/tools:1.2.3"), err.toString());
    }
}
