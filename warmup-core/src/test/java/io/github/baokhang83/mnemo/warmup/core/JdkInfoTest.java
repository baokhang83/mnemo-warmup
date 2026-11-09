package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkInfoTest {

    private static final String SAMPLE = """
            Property settings:
                file.encoding = UTF-8
                java.class.path = 
                java.home = /opt/jdk-25
                java.runtime.version = 25.0.1+8-27
                java.vendor = Eclipse Adoptium
                java.vendor.url = https://adoptium.net/
                java.version = 25.0.1
                java.vm.name = OpenJDK 64-Bit Server VM
                jdk.module.path = /a
                    /b

            openjdk version "25.0.1" 2025-10-21
            """;

    @Test
    void parsesSettingsOutput() {
        JdkInfo info = JdkInfo.parse(SAMPLE);
        assertEquals(25, info.featureVersion());
        assertEquals("25.0.1", info.version());
        assertEquals("Eclipse Adoptium", info.vendor());
        assertEquals("25.0.1+8-27", info.runtimeVersion());
        assertEquals(Path.of("/opt/jdk-25"), info.javaHome());
    }

    @Test
    void parseRejectsOutputWithoutVersion() {
        assertThrows(IllegalArgumentException.class, () -> JdkInfo.parse("nothing useful"));
    }

    @Test
    void parsesFeatureVersions() {
        assertEquals(21, JdkInfo.parseFeatureVersion("21.0.5"));
        assertEquals(21, JdkInfo.parseFeatureVersion("21"));
        assertEquals(25, JdkInfo.parseFeatureVersion("25-ea"));
        assertEquals(8, JdkInfo.parseFeatureVersion("1.8.0_292"));
        assertThrows(IllegalArgumentException.class, () -> JdkInfo.parseFeatureVersion("abc"));
    }

    @Test
    void currentMatchesRunningJvm() {
        JdkInfo info = JdkInfo.current();
        assertEquals(Runtime.version().feature(), info.featureVersion());
        assertNotNull(info.vendor());
    }

    @Test
    void probesARealJavaHome() {
        Path home = Path.of(System.getProperty("java.home"));
        JdkInfo info = JdkInfo.fromJavaHome(home);
        assertEquals(Runtime.version().feature(), info.featureVersion());
        assertEquals(home, info.javaHome());
    }

    @Test
    void explicitJavaHomeWinsOverEnvironment() {
        Path home = Path.of(System.getProperty("java.home"));
        JdkInfo info = JdkInfo.detect(home, Map.of("JAVA_HOME", "/does/not/exist"));
        assertEquals(Runtime.version().feature(), info.featureVersion());
    }

    @Test
    void usesJavaHomeFromEnvironment() {
        String home = System.getProperty("java.home");
        JdkInfo info = JdkInfo.detect(null, Map.of("JAVA_HOME", home));
        assertEquals(Runtime.version().feature(), info.featureVersion());
    }

    @Test
    void fallsBackToRunningJvm() {
        assertEquals(JdkInfo.current(), JdkInfo.detect(null, Map.of()));
        assertEquals(JdkInfo.current(), JdkInfo.detect(null, Map.of("JAVA_HOME", "  ")));
    }

    @Test
    void rejectsJavaHomeWithoutJava(@TempDir Path empty) {
        assertThrows(IllegalArgumentException.class, () -> JdkInfo.fromJavaHome(empty));
    }
}
