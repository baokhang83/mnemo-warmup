package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IsolatedPublishCommandTest {
    @Test void targetOptionsAreDataAndNeverInheritThePublishersOwnJvmOptions() {
        var target = Main.IsolatedPublish.targetJavaEnvironment(Map.of(
                "JAVA_TOOL_OPTIONS", "-javaagent:/untrusted.jar",
                "JDK_JAVA_OPTIONS", "-Xmx1g",
                "MNEMO_TARGET_JAVA_TOOL_OPTIONS", "-Xmx512m",
                "MNEMO_TARGET_JDK_JAVA_OPTIONS", "-XX:+UseG1GC"));
        assertEquals(Map.of("JAVA_TOOL_OPTIONS", "-Xmx512m", "JDK_JAVA_OPTIONS", "-XX:+UseG1GC"), target);
        assertFalse(Main.IsolatedPublish.targetJavaEnvironment(Map.of("JAVA_TOOL_OPTIONS", "-Xmx1g"))
                .containsKey("JAVA_TOOL_OPTIONS"));
    }

    @Test void missingWorkloadIsRejectedBeforePublication() {
        var cmd = Main.commandLine();
        cmd.setOut(new PrintWriter(new StringWriter(), true));
        cmd.setErr(new PrintWriter(new StringWriter(), true));
        assertEquals(1, cmd.execute("publish-isolated", "--store", "oci://example.com/team/app", "--archive", "/tmp/a"));
    }
}
