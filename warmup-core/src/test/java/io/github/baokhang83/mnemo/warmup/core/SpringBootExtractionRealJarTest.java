package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Extracts and trains a real Spring Boot 3.3+ fat JAR (JDK of this test run). Opt-in because the repository does not ship one:
 * {@code mvn verify -Dwarmup.test.springBootJar=/path/to/app.jar}.
 */
class SpringBootExtractionRealJarTest {

    @Test
    void extractsARealSpringBootJar(@TempDir Path dir) {
        String property = System.getProperty("warmup.test.springBootJar");
        assumeTrue(property != null && !property.isBlank(), "set -Dwarmup.test.springBootJar to run");

        Path dest = dir.resolve("out");
        var app = new JarExtractor().prepare(Path.of(property), JdkInfo.current(), dest);

        assertEquals(JarExtractor.Kind.SPRING_BOOT, app.kind());
        assertTrue(Files.isRegularFile(app.runnableJar()), "extracted jar missing: " + app.runnableJar());
        assertTrue(Files.isDirectory(dest.resolve("lib")), "lib/ directory missing");

        JdkInfo jdk = JdkInfo.current();
        CacheStrategy strategy = CacheType.AUTO.resolve(jdk);
        Path archive = dir.resolve("app." + strategy.extension());
        var trained = new AppTrainer().train(
                app, jdk, strategy, new SpringRefreshExitMode(), archive, java.time.Duration.ofMinutes(3));
        assertTrue(trained.sizeBytes() > 0, "no archive for the real Spring Boot app");
    }
}
