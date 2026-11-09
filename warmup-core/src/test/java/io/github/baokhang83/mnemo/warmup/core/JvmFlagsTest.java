package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JvmFlagsTest {

    @Test
    void keepsHeapAndGcOptionsAndDropsTheRest() {
        List<String> flags = JvmFlags.relevant(
                List.of("-Xmx512m", "-Dfoo=bar", "-XX:+UseG1GC", "-verbose:gc", "-XX:MaxRAMPercentage=75.0",
                        "-XX:+HeapDumpOnOutOfMemoryError", "-Duser.timezone=UTC"),
                Map.of());

        assertEquals(List.of("-XX:MaxRAMPercentage=75.0", "-XX:+UseG1GC", "-Xmx512m"), flags);
    }

    @Test
    void recognisesTheLayoutAndSizingOptions() {
        List<String> all = List.of(
                "-Xms64m", "-Xmx1g", "-XX:+UseZGC", "-XX:+UseSerialGC", "-XX:-UseCompressedOops",
                "-XX:+UseCompactObjectHeaders", "-XX:ObjectAlignmentInBytes=16", "-XX:SoftMaxHeapSize=2g",
                "-XX:InitialRAMPercentage=50", "-XX:MaxHeapSize=1g");
        assertEquals(all.size(), JvmFlags.relevant(all, Map.of()).size());
    }

    @Test
    void lastOccurrenceWins() {
        assertEquals(List.of("-Xmx2g"), JvmFlags.relevant(List.of("-Xmx512m", "-Xmx2g"), Map.of()));
        assertEquals(List.of("-XX:-UseG1GC"), JvmFlags.relevant(List.of("-XX:+UseG1GC", "-XX:-UseG1GC"), Map.of()));
        assertEquals(List.of("-XX:MaxRAMPercentage=50"),
                JvmFlags.relevant(List.of("-XX:MaxRAMPercentage=75", "-XX:MaxRAMPercentage=50"), Map.of()));
    }

    @Test
    void xmsAndXmxAreIndependent() {
        assertEquals(List.of("-Xms1g", "-Xmx2g"), JvmFlags.relevant(List.of("-Xmx2g", "-Xms1g"), Map.of()));
    }

    @Test
    void orderOfTheCommandLineDoesNotMatter() {
        assertEquals(
                JvmFlags.relevant(List.of("-Xmx1g", "-XX:+UseZGC"), Map.of()),
                JvmFlags.relevant(List.of("-XX:+UseZGC", "-Xmx1g"), Map.of()));
    }

    @Test
    void readsTheOptionVariablesTheJvmReads() {
        List<String> flags = JvmFlags.relevant(
                List.of("-Xmx512m"),
                Map.of("JAVA_TOOL_OPTIONS", " -XX:+UseParallelGC  -Dx=y ", "JDK_JAVA_OPTIONS", "-Xms128m", "OTHER", "-Xmx9g"));
        assertEquals(List.of("-XX:+UseParallelGC", "-Xms128m", "-Xmx512m"), flags);
    }

    @Test
    void theEnvironmentOverridesLikeTheJvmDoes() {
        // JAVA_TOOL_OPTIONS is applied after the command line's own options in this model.
        assertEquals(List.of("-Xmx1g"), JvmFlags.relevant(List.of("-Xmx512m"), Map.of("JAVA_TOOL_OPTIONS", "-Xmx1g")));
    }
}
