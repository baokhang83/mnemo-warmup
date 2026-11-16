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
        PhaseTimer timer = PhaseTimer.from(environment);
        try {
            Plan plan = decide(javaCommand, cacheDir, environment, imageDigest, notes, timer);
            timer.summary().ifPresent(notes::add);
            return plan;
        } catch (RuntimeException e) {
            notes.add("could not validate the archive (" + e.getMessage() + "); starting without it");
            timer.summary().ifPresent(notes::add);
            return new Plan(List.copyOf(javaCommand), false, notes);
        }
    }

    private Plan decide(List<String> command, Path cacheDir, Map<String, String> env, String imageDigest, List<String> notes, PhaseTimer timer) {
        Plan plain = new Plan(List.copyOf(command), false, notes);
        Path flagsFile = cacheDir.resolve(ArchiveFetcher.FLAGS_FILE);
        String flags = readOrNull(flagsFile, MAX_FLAGS_BYTES);
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
        // One bounded snapshot, read once: it is parsed here and hashed for the signature below, so both see the same bytes even if a
        // writer grows or replaces the file after this point. (A size check followed by two reads would let it change in between.)
        byte[] manifestBytes = snapshot(manifestFile, MAX_MANIFEST_BYTES);
        if (manifestBytes == null) {
            notes.add("the manifest next to the flags is unreadable or larger than anything fetch writes; starting without an archive");
            return plain;
        }
        ArchiveManifest trained = ArchiveManifest.fromJson(new String(manifestBytes, StandardCharsets.UTF_8));
        timer.lap("read-manifest");
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
        String archiveSha256 = sha256Snapshot(archive, trained.archive().sizeBytes());
        timer.lap("archive-sha256");
        if (archiveSha256 == null) {
            notes.add("archive " + archive + " could not be read as the " + trained.archive().sizeBytes() + " bytes the manifest declares; starting without it");
            return plain;
        }
        if (!trained.archive().sha256().equals(archiveSha256)) {
            notes.add("archive " + archive + " does not match the SHA-256 in its manifest; starting without it");
            return plain;
        }
        // Signed archives (docs/adr/0013): when the operator lists public keys, the files on disk must still carry a valid signature.
        // Anything able to write into this directory can swap the files, but cannot make a signature for the swap.
        // A variable that is present enforces, even when blank: an empty value from a template or a typo must never switch the check off.
        // A list with nothing usable then trusts nothing, which only costs the cache.
        String keys = env.get(ArchiveVerifier.ENV_PUBLIC_KEYS);
        if (keys != null) {
            String problem = signatureProblem(ArchiveVerifier.fromEntries(keys, notes::add), cacheDir, manifestBytes, trained, archive, archiveSha256);
            timer.lap("signature");
            if (problem != null) {
                notes.add("the archive is not trusted: " + problem + "; starting without it");
                return plain;
            }
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
        if (JarExtractor.needsExtraction(launch.jar())) {
            notes.add("a Spring Boot fat JAR is launched directly; the archive was trained on the extracted layout, "
                    + "so extract at image build time (java -Djarmode=tools -jar app.jar extract) and run the extracted JAR");
            return plain;
        }

        JdkInfo jdk = JdkInfo.fromJavaHome(launch.javaHome(env), PROBE_TIMEOUT);
        timer.lap("jdk-probe");
        var live = ArchiveManifest.describe(
                new JarExtractor.ExtractedApp(JarExtractor.Kind.EXECUTABLE_JAR, launch.jar().toAbsolutePath()),
                jdk, trained.cacheType(), launch.jvmOptions(), env, imageDigest);
        timer.lap("classpath-hash");
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

    /**
     * Why the files in the cache directory are not covered by a valid signature, or null when they are. The statement is rebuilt from the
     * files as they are now (the manifest's fingerprint, the SHA-256 of the manifest file's bytes, the archive's SHA-256 and size) and the
     * repository the signature file names, never from anything the signature file claims about them.
     */
    private static String signatureProblem(
            ArchiveVerifier verifier, Path cacheDir, byte[] manifestBytes, ArchiveManifest trained, Path archive, String archiveSha256) {
        String text = readOrNull(cacheDir.resolve(SignatureFile.FILE_NAME), MAX_SIGNATURE_BYTES);
        if (text == null) {
            return "there is no usable signature next to it";
        }
        SignatureFile.Contents contents;
        try {
            contents = SignatureFile.parse(text);
        } catch (IllegalArgumentException e) {
            return "the signature file is unreadable";
        }
        var statement = new SignedStatement(
                trained.fingerprint(), "sha256:" + sha256Hex(manifestBytes), "sha256:" + archiveSha256, trained.archive().sizeBytes(), contents.repository());
        return switch (verifier.verify(statement, contents.signature())) {
            case VALID -> null;
            case UNKNOWN_KEY -> "it is signed by a key that is not trusted (" + contents.signature().keyId() + ")";
            case BAD_SIGNATURE -> "its signature does not match the files on disk";
        };
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // The files in the cache directory are written by fetch but live in a volume other processes may be able to write. They are small, so a
    // bigger one is not what fetch wrote, and reading it whole could exhaust memory on the application's start path.
    private static final int MAX_FLAGS_BYTES = 4 << 10;
    private static final int MAX_SIGNATURE_BYTES = 4 << 10;
    private static final long MAX_MANIFEST_BYTES = 8L << 20;

    /** How long opening one of these files may take before it is given up on (opening a named pipe waits for a writer, forever). */
    static volatile long readTimeoutMillis = 5_000;
    /** How long hashing the archive may take once it is open. A 1 GiB archive takes a few seconds; a writer trickling bytes takes forever. */
    static volatile long hashTimeoutMillis = 120_000;
    /**
     * A timed-out read cannot be cancelled: a thread blocked opening a named pipe stays blocked until some writer opens it or the process
     * exits. The planner is a short process that exits right after it prints the command, so this costs one thread for a few seconds. For
     * a long-lived process that calls the planner repeatedly the number of blocked readers is capped: once that many are stuck, reads are
     * refused (a plain start) instead of accumulating more.
     */
    static volatile int maxBlockedReaders = 8;
    private static final java.util.concurrent.atomic.AtomicInteger LIVE_READERS = new java.util.concurrent.atomic.AtomicInteger();

    static int liveReaders() {
        return LIVE_READERS.get();
    }

    /** The file's text, or null when it is missing, unreadable, larger than {@code max} bytes, or does not answer in time. */
    private static String readOrNull(Path file, int max) {
        byte[] bytes = snapshot(file, max);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * The file's bytes read once, or null when it is missing, is a directory, is larger than {@code max} bytes, or does not finish within
     * {@link #readTimeoutMillis}. The cache directory is a volume other processes may be able to write, so a file here is never read
     * without a bound. The read runs on a daemon thread: opening a named pipe blocks until someone writes to it, and Java cannot open a
     * file without blocking, so the time limit is what keeps the application's start from hanging.
     */
    static byte[] snapshot(Path file, long max) {
        var result = new java.util.concurrent.CompletableFuture<byte[]>();
        if (!startReader(() -> {
            try (var in = Files.newInputStream(file)) {
                byte[] bytes = in.readNBytes((int) (max + 1));
                result.complete(bytes.length > max ? null : bytes);
            } catch (IOException | RuntimeException e) {
                result.complete(null);
            }
        })) {
            return null;
        }
        try {
            return result.get(readTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * The SHA-256 of an archive that is exactly {@code expectedSize} bytes, or null when it cannot be opened in time, is a different size
     * (longer, or shorter), or cannot be read. The archive is opened here, not checked with {@code isRegularFile} first: a check followed by
     * an open leaves a moment in which the path can be swapped for a named pipe, and opening one would block the planner.
     */
    static String sha256Snapshot(Path file, long expectedSize) {
        var opened = new java.util.concurrent.CompletableFuture<Boolean>();
        var result = new java.util.concurrent.CompletableFuture<String>();
        if (!startReader(() -> {
            try (var in = Files.newInputStream(file)) {
                opened.complete(true);
                var md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (int n; (n = in.read(buffer)) >= 0; ) {
                    total += n;
                    if (total > expectedSize) {
                        result.complete(null);
                        return;
                    }
                    md.update(buffer, 0, n);
                }
                result.complete(total == expectedSize ? java.util.HexFormat.of().formatHex(md.digest()) : null);
            } catch (IOException | java.security.NoSuchAlgorithmException | RuntimeException e) {
                opened.complete(false);
                result.complete(null);
            }
        })) {
            return null;
        }
        try {
            if (!opened.get(readTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return null;
            }
            return result.get(hashTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Runs {@code task} on a daemon thread, unless too many earlier readers are still stuck (see {@link #maxBlockedReaders}). */
    static boolean startReader(Runnable task) {
        int readers;
        do {
            readers = LIVE_READERS.get();
            if (readers >= maxBlockedReaders) {
                return false;
            }
        } while (!LIVE_READERS.compareAndSet(readers, readers + 1));
        try {
            Thread reader = new Thread(() -> {
                try {
                    task.run();
                } finally {
                    LIVE_READERS.decrementAndGet();
                }
            }, "mnemo-warmup-read");
            reader.setDaemon(true);
            reader.start();
            return true;
        } catch (RuntimeException | Error e) {
            LIVE_READERS.decrementAndGet();
            throw e;
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
