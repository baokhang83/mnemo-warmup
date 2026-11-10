package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decides how the application container starts the JVM: with the archive that {@code fetch} left in a
 * cache directory when it still matches this exact launch, otherwise exactly as given. It never
 * throws and never removes anything from the command; every reason for not using the archive is a
 * note (constitution §1 and §3).
 */
public final class RunPlanner {

    /**
     * @param command      what to start: the original command, with the archive flag inserted after
     *                     the java executable when {@code archiveUsed}
     * @param archiveUsed  whether the flag was added
     * @param notes        why the archive was or was not used, for the log
     */
    public record Plan(List<String> command, boolean archiveUsed, List<String> notes) {}

    /** A healthy probe takes a fraction of a second; a hung launcher must not hold the application back for long. */
    private static final java.time.Duration PROBE_TIMEOUT = java.time.Duration.ofSeconds(10);

    private static final String[] ARCHIVE_OPTIONS = {"-XX:SharedArchiveFile=", "-XX:AOTCache=", "-XX:ArchiveClassesAtExit=", "-XX:AOTCacheOutput="};

    /**
     * @param javaCommand  {@code java [jvm options] -jar app.jar [args]}
     * @param cacheDir     the directory {@code fetch --out} wrote
     * @param imageDigest  the container image digest as given to {@code build}, or null
     */
    public Plan plan(List<String> javaCommand, Path cacheDir, Map<String, String> environment, String imageDigest) {
        List<String> notes = new ArrayList<>();
        try {
            return decide(javaCommand, cacheDir, environment, imageDigest, notes);
        } catch (RuntimeException e) {
            notes.add("could not validate the archive (" + e.getMessage() + "); starting without it");
            return new Plan(List.copyOf(javaCommand), false, notes);
        }
    }

    private Plan decide(List<String> command, Path cacheDir, Map<String, String> env, String imageDigest, List<String> notes) {
        Plan plain = new Plan(List.copyOf(command), false, notes);
        Path flagsFile = cacheDir.resolve(ArchiveFetcher.FLAGS_FILE);
        String flags = readOrNull(flagsFile);
        if (flags == null || flags.isBlank()) {
            notes.add("no archive was fetched into " + cacheDir + "; starting without one");
            return plain;
        }
        String flag = flags.strip();
        if (flag.contains(" ") || flag.contains("\n")) {
            notes.add("unexpected flags file content; starting without an archive");
            return plain;
        }
        Path manifestFile = cacheDir.resolve(ArchiveManifest.FILE_NAME);
        if (!Files.isRegularFile(manifestFile)) {
            notes.add("no manifest next to the flags; starting without an archive");
            return plain;
        }
        ArchiveManifest trained = ArchiveManifest.read(manifestFile);
        if (!flag.equals(trained.cacheType().runtimeFlag(flag.substring(flag.indexOf('=') + 1)))) {
            notes.add("flags (" + flag + ") do not match the manifest's cache type " + trained.cacheType() + "; starting without an archive");
            return plain;
        }
        Path archive = Path.of(flag.substring(flag.indexOf('=') + 1));
        if (!Files.isRegularFile(archive) || trained.archive() == null || sizeOf(archive) != trained.archive().sizeBytes()) {
            notes.add("archive " + archive + " is missing or its size differs from the manifest; starting without it");
            return plain;
        }
        // Verify before use (§2): the size alone would accept same-length corruption, so hash the bytes
        // that are about to be handed to the JVM.
        if (!trained.archive().sha256().equals(sha256Hex(archive))) {
            notes.add("archive " + archive + " does not match the SHA-256 in its manifest; starting without it");
            return plain;
        }

        JavaLaunch launch = JavaLaunch.parse(command);
        if (launch == null) {
            notes.add("not a 'java ... -jar <jar>' launch; starting without an archive");
            return plain;
        }
        for (String arg : command) {
            for (String option : ARCHIVE_OPTIONS) {
                if (arg.startsWith(option)) {
                    notes.add("the command already sets " + option + "; leaving it alone");
                    return plain;
                }
            }
        }
        if (JarExtractor.detect(launch.jar()) == JarExtractor.Kind.SPRING_BOOT) {
            notes.add("a Spring Boot fat JAR is launched directly; the archive was trained on the extracted layout, "
                    + "so extract at image build time (java -Djarmode=tools -jar app.jar extract) and run the extracted JAR");
            return plain;
        }

        JdkInfo jdk = JdkInfo.fromJavaHome(launch.javaHome(env), PROBE_TIMEOUT);
        var live = ArchiveManifest.describe(
                new JarExtractor.ExtractedApp(JarExtractor.Kind.EXECUTABLE_JAR, launch.jar().toAbsolutePath()),
                jdk, trained.cacheType(), launch.jvmOptions(), env, imageDigest);
        List<String> problems = trained.mismatches(live);
        if (!problems.isEmpty()) {
            notes.add("archive does not match this launch: " + problems + "; starting without it");
            return plain;
        }
        List<String> locationProblems = trained.locationMismatches(live);
        if (!locationProblems.isEmpty()) {
            notes.add("the JVM records JAR paths, so it would ignore the archive: " + locationProblems + "; starting without it");
            return plain;
        }
        List<String> timeProblems = trained.modificationTimeMismatches(live);
        if (!timeProblems.isEmpty()) {
            notes.add("the JVM would ignore the archive because JAR modification times differ: " + timeProblems
                    + "; train and run from the same files; starting without it");
            return plain;
        }
        List<String> withFlag = new ArrayList<>(command);
        withFlag.add(1, flag);
        notes.add("using " + trained.cacheType() + " archive " + archive);
        return new Plan(List.copyOf(withFlag), true, notes);
    }

    private static String readOrNull(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String sha256Hex(Path file) {
        try (var in = Files.newInputStream(file)) {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                md.update(buffer, 0, n);
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot hash " + file + ": " + e.getMessage(), e);
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    /** The parts of a {@code java ... -jar app.jar} command that decide archive validity. */
    record JavaLaunch(String executable, List<String> jvmOptions, Path jar) {

        /** Null unless the command is {@code <java> [-options] -jar <jar> ...}. */
        static JavaLaunch parse(List<String> command) {
            if (command.size() < 3) {
                return null;
            }
            String exe = command.get(0);
            String name = Path.of(exe).getFileName().toString();
            if (!name.equals("java") && !name.equals("java.exe")) {
                return null;
            }
            List<String> options = new ArrayList<>();
            for (int i = 1; i < command.size(); i++) {
                String arg = command.get(i);
                if (arg.equals("-jar")) {
                    return i + 1 < command.size() ? new JavaLaunch(exe, List.copyOf(options), Path.of(command.get(i + 1))) : null;
                }
                if (!arg.startsWith("-")) {
                    return null; // a main class: not a -jar launch
                }
                if (arg.equals("-cp") || arg.equals("-classpath") || arg.equals("--class-path")) {
                    return null; // classpath launches are not supported
                }
                options.add(arg);
            }
            return null;
        }

        /** The java home of the launched executable: its path if given, else the first {@code java} on PATH, else JAVA_HOME. */
        Path javaHome(Map<String, String> env) {
            Path bin = null;
            if (executable.contains("/") || executable.contains("\\")) {
                bin = Path.of(executable);
            } else {
                String path = env.get("PATH");
                if (path != null) {
                    for (String dir : path.split(java.io.File.pathSeparator)) {
                        Path candidate = Path.of(dir.isEmpty() ? "." : dir).resolve(executable);
                        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                            bin = candidate;
                            break;
                        }
                    }
                }
            }
            if (bin != null) {
                try {
                    Path home = bin.toRealPath().getParent().getParent();
                    if (home != null && Files.isRegularFile(home.resolve("bin").resolve(bin.getFileName()))) {
                        return home;
                    }
                } catch (IOException ignored) {
                    // fall through to JAVA_HOME
                }
            }
            String fromEnv = env.get("JAVA_HOME");
            if (fromEnv != null && !fromEnv.isBlank()) {
                return Path.of(fromEnv);
            }
            return JdkInfo.current().javaHome();
        }
    }
}
