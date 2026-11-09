package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Facts about a JDK that the cache must be bound to: the JDK of the <em>target application</em>,
 * which can differ from the JDK running this tool.
 *
 * @param javaHome       the JDK home directory (may be {@code null} if unknown)
 * @param version        the {@code java.version} property, e.g. {@code 25.0.1}
 * @param featureVersion the feature release, e.g. {@code 21}, {@code 25}
 * @param vendor         the {@code java.vendor} property
 * @param runtimeVersion the {@code java.runtime.version} property (full build string)
 */
public record JdkInfo(Path javaHome, String version, int featureVersion, String vendor, String runtimeVersion) {

    private static final Pattern PROPERTY_LINE = Pattern.compile("^ {4}(\\S+) = (.*)$");
    private static final Pattern LEADING_VERSION = Pattern.compile("^(\\d+)(?:\\.(\\d+))?");
    private static final long PROBE_TIMEOUT_SECONDS = 30;

    /** Describes the JDK this process is running on. */
    public static JdkInfo current() {
        String version = System.getProperty("java.version");
        return new JdkInfo(
                Path.of(System.getProperty("java.home")),
                version,
                parseFeatureVersion(version),
                System.getProperty("java.vendor", "unknown"),
                System.getProperty("java.runtime.version", version));
    }

    /**
     * Resolves the target JDK: an explicit Java home wins, then {@code JAVA_HOME} from the given
     * environment, then the JDK running this process.
     *
     * @throws IllegalArgumentException if a chosen Java home has no {@code bin/java}
     * @throws IllegalStateException    if the JDK cannot be probed
     */
    public static JdkInfo detect(Path explicitJavaHome, Map<String, String> env) {
        if (explicitJavaHome != null) {
            return fromJavaHome(explicitJavaHome);
        }
        String fromEnv = env.get("JAVA_HOME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromJavaHome(Path.of(fromEnv));
        }
        return current();
    }

    /** Probes {@code <javaHome>/bin/java} by asking it for its system properties. */
    public static JdkInfo fromJavaHome(Path javaHome) {
        Path java = resolveJavaExecutable(javaHome);
        String output = run(java);
        JdkInfo parsed = parse(output);
        return new JdkInfo(javaHome, parsed.version(), parsed.featureVersion(), parsed.vendor(), parsed.runtimeVersion());
    }

    /** Parses the output of {@code java -XshowSettings:properties -version}. */
    static JdkInfo parse(String settingsOutput) {
        Map<String, String> props = new HashMap<>();
        for (String line : settingsOutput.split("\\R")) {
            Matcher m = PROPERTY_LINE.matcher(line);
            if (m.matches()) {
                props.put(m.group(1), m.group(2).trim());
            }
        }
        String version = props.get("java.version");
        if (version == null) {
            throw new IllegalArgumentException("No java.version found in JVM settings output");
        }
        String home = props.get("java.home");
        return new JdkInfo(
                home == null ? null : Path.of(home),
                version,
                parseFeatureVersion(version),
                props.getOrDefault("java.vendor", "unknown"),
                props.getOrDefault("java.runtime.version", version));
    }

    /** Extracts the feature release: {@code 21.0.5 -> 21}, {@code 25-ea -> 25}, {@code 1.8.0_292 -> 8}. */
    static int parseFeatureVersion(String version) {
        Matcher m = LEADING_VERSION.matcher(version.trim());
        if (!m.find()) {
            throw new IllegalArgumentException("Cannot parse Java version: " + version);
        }
        int major = Integer.parseInt(m.group(1));
        if (major == 1 && m.group(2) != null) {
            return Integer.parseInt(m.group(2));
        }
        return major;
    }

    private static Path resolveJavaExecutable(Path javaHome) {
        for (String name : new String[] {"java", "java.exe"}) {
            Path candidate = javaHome.resolve("bin").resolve(name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("No java executable found under " + javaHome.resolve("bin"));
    }

    private static String run(Path java) {
        try {
            Process process = new ProcessBuilder(java.toString(), "-XshowSettings:properties", "-version")
                    .redirectErrorStream(true)
                    .start();
            // The JVM writes the settings to stderr; merged into stdout above.
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Timed out probing " + java);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Probing " + java + " failed with exit code " + process.exitValue());
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot run " + java, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while probing " + java, e);
        }
    }
}
