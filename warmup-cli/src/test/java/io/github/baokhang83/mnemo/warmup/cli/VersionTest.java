package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class VersionTest {

    @Test
    void formatsTheManifestVersionOrSaysDev() {
        assertEquals("mnemo-warmup 0.2.0", Main.VersionProvider.format("0.2.0"));
        assertEquals("mnemo-warmup dev", Main.VersionProvider.format(null));
        assertEquals("mnemo-warmup dev", Main.VersionProvider.format("  "));
    }

    @Test
    void theVersionOptionPrintsItAndExitsZero() {
        StringWriter out = new StringWriter();
        var cmd = Main.commandLine();
        cmd.setOut(new PrintWriter(out, true));
        assertEquals(0, cmd.execute("--version"));
        assertTrue(out.toString().startsWith("mnemo-warmup "), out.toString());
        assertTrue(!out.toString().contains("0.1.0-SNAPSHOT"), "no hard-coded version string: " + out);
    }
}
