package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code run} checks the files on disk again at every start (docs/adr/0013). The attacker here can write into the cache directory, which
 * is shared inside the pod, and takes care to keep every digest check passing; only a signature they cannot make stops them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunPlannerSignatureTest {

    private static final JdkInfo JDK = JdkInfo.fromJavaHome(Path.of(System.getProperty("java.home")));
    private static final CacheStrategy STRATEGY = CacheType.AUTO.resolve(JDK);
    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    private static final String REPOSITORY = "team/app";

    private Path templateJar;

    @BeforeAll
    void buildTemplate(@TempDir Path shared) throws IOException {
        templateJar = TestApps.jar(shared, "template", TestApps.HELLO);
    }

    private record Cache(Path jar, Path cacheDir, Path archive, Path manifestFile, ArchiveManifest manifest) {}

    /** What fetch leaves behind, without a signature file yet. */
    private Cache cache(Path dir) throws IOException {
        Path jar = Files.copy(templateJar, dir.resolve("app.jar"));
        Files.setLastModifiedTime(jar, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Path cacheDir = Files.createDirectories(dir.resolve("cache"));
        Path archive = Files.writeString(cacheDir.resolve("app." + STRATEGY.extension()), "the genuine archive");
        var manifest = ArchiveManifest.describe(
                        new JarExtractor.ExtractedApp(JarExtractor.Kind.EXECUTABLE_JAR, jar), JDK, STRATEGY, List.of("-Xmx512m"), Map.of(), null)
                .withArchive(archive);
        Path manifestFile = cacheDir.resolve(ArchiveManifest.FILE_NAME);
        manifest.write(manifestFile);
        Files.writeString(cacheDir.resolve(ArchiveFetcher.FLAGS_FILE), STRATEGY.runtimeFlag(archive.toAbsolutePath().toString()) + "\n");
        return new Cache(jar, cacheDir, archive, manifestFile, manifest);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Signs what is on disk now, as the trainer would have, and writes the signature file as fetch would. */
    private static void sign(Cache c, SigningKey key, String repository) throws Exception {
        var statement = new SignedStatement(c.manifest().fingerprint(), sha256(Files.readAllBytes(c.manifestFile())),
                sha256(Files.readAllBytes(c.archive())), Files.size(c.archive()), repository);
        Files.writeString(c.cacheDir().resolve(SignatureFile.FILE_NAME), SignatureFile.text(key.sign(statement), repository));
    }

    private static Map<String, String> trusting(SigningKey... keys) {
        StringBuilder entries = new StringBuilder();
        for (SigningKey k : keys) {
            entries.append(entries.length() == 0 ? "" : ",").append(k.publicKeyEntry());
        }
        return Map.of(ArchiveVerifier.ENV_PUBLIC_KEYS, entries.toString());
    }

    private static List<String> launch(Cache c) {
        List<String> command = new ArrayList<>(List.of(JAVA, "-Xmx512m"));
        command.addAll(List.of("-jar", c.jar().toString(), "--server.port=8080"));
        return command;
    }

    private static RunPlanner.Plan plan(Cache c, Map<String, String> env) {
        return new RunPlanner().plan(launch(c), c.cacheDir(), env, null);
    }

    private static void assertPlainStart(Cache c, RunPlanner.Plan plan, String reason) {
        assertFalse(plan.archiveUsed(), plan.notes().toString());
        assertEquals(launch(c), plan.command(), "the command must be exactly what was given");
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains(reason)), reason + " expected in " + plan.notes());
    }

    @Test
    void aSignedCacheDirectoryIsUsed(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        SigningKey key = SigningKey.generate();
        sign(c, key, REPOSITORY);
        var plan = plan(c, trusting(key));
        assertTrue(plan.archiveUsed(), plan.notes().toString());
        assertTrue(plan.command().get(1).startsWith(STRATEGY.runtimeFlag("")), plan.command().toString());
    }

    @Test
    void withoutAKeyListNothingIsRequired(@TempDir Path dir) throws Exception {
        Cache c = cache(dir); // no signature file at all
        assertTrue(plan(c, Map.of()).archiveUsed());
        // (a variable that is present but blank is not "no key list": see aPresentButBlankKeyListTrustsNothing)
    }

    @Test
    void aMissingSignatureFileMeansAPlainStart(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        assertPlainStart(c, plan(c, trusting(SigningKey.generate())), "no usable signature next to it");
    }

    // the attack in the design: swap the archive AND rewrite the manifest so its hash and size match the new archive
    @Test
    void aSwappedArchiveWithAMatchingManifestKeepsNoValidSignature(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        SigningKey key = SigningKey.generate();
        sign(c, key, REPOSITORY);
        Files.writeString(c.archive(), "an archive the attacker built, of any size");
        ArchiveManifest rewritten = c.manifest().withArchive(c.archive());
        rewritten.write(c.manifestFile());
        // every digest check still passes: the manifest says exactly what the new archive is
        assertPlainStart(c, plan(c, trusting(key)), "signature does not match the files on disk");
    }

    // a signature covers the manifest file's exact bytes, so even a change that means nothing to the parser is caught
    @Test
    void aManifestEditedByAByteInvalidatesTheSignature(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        SigningKey key = SigningKey.generate();
        sign(c, key, REPOSITORY);
        Files.writeString(c.manifestFile(), Files.readString(c.manifestFile()) + "\n ", StandardCharsets.UTF_8);
        assertPlainStart(c, plan(c, trusting(key)), "signature does not match the files on disk");
    }

    @Test
    void anArchiveSignedByAKeyThatIsNotTrustedIsRefused(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        SigningKey attacker = SigningKey.generate();
        sign(c, attacker, REPOSITORY);
        var plan = plan(c, trusting(SigningKey.generate()));
        assertPlainStart(c, plan, "not trusted");
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains(attacker.keyId())), plan.notes().toString());
    }

    // pointing the signature file at another repository changes the statement, so the signature no longer matches
    @Test
    void aSignatureFileNamingAnotherRepositoryIsRefused(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        SigningKey key = SigningKey.generate();
        sign(c, key, REPOSITORY);
        Path file = c.cacheDir().resolve(SignatureFile.FILE_NAME);
        Files.writeString(file, Files.readString(file).replace("repository: team/app", "repository: team/other"));
        assertPlainStart(c, plan(c, trusting(key)), "signature does not match the files on disk");
    }

    @Test
    void anUnreadableSignatureFileIsAPlainStartNotAnError(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        Files.writeString(c.cacheDir().resolve(SignatureFile.FILE_NAME), "garbage");
        assertPlainStart(c, plan(c, trusting(SigningKey.generate())), "unreadable");
    }

    @Test
    void aKeyListWithNothingUsableRefusesEvenASignedDirectory(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        sign(c, SigningKey.generate(), REPOSITORY);
        var plan = plan(c, Map.of(ArchiveVerifier.ENV_PUBLIC_KEYS, "typo-not-a-key"));
        assertFalse(plan.archiveUsed(), plan.notes().toString());
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("ignoring a signing public key entry")), plan.notes().toString());
    }

    @Test
    void severalTrustedKeysSupportRotation(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        SigningKey oldKey = SigningKey.generate();
        SigningKey newKey = SigningKey.generate();
        sign(c, newKey, REPOSITORY);
        assertTrue(plan(c, trusting(oldKey, newKey)).archiveUsed());
        assertFalse(plan(c, trusting(oldKey)).archiveUsed());
    }

    // a variable that is present enforces even when blank: an empty value from a template must never switch the check off
    @Test
    void aPresentButBlankKeyListTrustsNothing(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        sign(c, SigningKey.generate(), REPOSITORY);
        for (String blank : new String[] {"", "   ", ","}) {
            var plan = plan(c, Map.of(ArchiveVerifier.ENV_PUBLIC_KEYS, blank));
            assertPlainStart(c, plan, "not trusted");
        }
    }

    // the cache directory is a volume other processes may write: a huge file is never read whole on the application's start path
    @Test
    void anOversizedSignatureFileIsAPlainStart(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        SigningKey key = SigningKey.generate();
        sign(c, key, REPOSITORY);
        Files.writeString(c.cacheDir().resolve(SignatureFile.FILE_NAME), "x".repeat(5_000));
        assertPlainStart(c, plan(c, trusting(key)), "no usable signature");
    }

    @Test
    void anOversizedFlagsFileOrManifestIsAPlainStart(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        SigningKey key = SigningKey.generate();
        sign(c, key, REPOSITORY);
        Files.writeString(c.cacheDir().resolve(ArchiveFetcher.FLAGS_FILE), "x".repeat(5_000));
        assertFalse(plan(c, trusting(key)).archiveUsed());

        Cache m = cache(Files.createDirectory(dir.resolve("second")));
        sign(m, key, REPOSITORY);
        try (var out = Files.newOutputStream(m.manifestFile())) {
            out.write(new byte[(8 << 20) + 1]);
        }
        var plan = plan(m, trusting(key));
        assertFalse(plan.archiveUsed());
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("larger than anything fetch writes")), plan.notes().toString());
    }

    @Test
    void aSignatureFileThatIsADirectoryIsAPlainStart(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        SigningKey key = SigningKey.generate();
        sign(c, key, REPOSITORY);
        Files.delete(c.cacheDir().resolve(SignatureFile.FILE_NAME));
        Files.createDirectory(c.cacheDir().resolve(SignatureFile.FILE_NAME));
        assertPlainStart(c, plan(c, trusting(key)), "no usable signature");
    }

    // ---- the cache directory is a volume other processes may write: every read of it is bounded in size and in time ----

    @Test
    void aSnapshotIsOneBoundedReadAndRefusesWhatIsTooBigOrNotAFile(@TempDir Path dir) throws Exception {
        Path exact = Files.write(dir.resolve("exact"), new byte[100]);
        Path tooBig = Files.write(dir.resolve("big"), new byte[101]);
        assertEquals(100, RunPlanner.snapshot(exact, 100).length, "exactly the limit is allowed");
        assertEquals(null, RunPlanner.snapshot(tooBig, 100));
        assertEquals(null, RunPlanner.snapshot(dir, 100), "a directory");
        assertEquals(null, RunPlanner.snapshot(dir.resolve("missing"), 100));
    }

    private static boolean makeFifo(Path path) {
        try {
            return new ProcessBuilder("mkfifo", path.toString()).redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    // Java cannot open a file without blocking, and opening a named pipe waits for a writer: the time limit is what keeps it from hanging
    @Test
    void aNamedPipeIsGivenUpOnInsteadOfHangingTheStart(@TempDir Path dir) throws Exception {
        Path pipe = dir.resolve("pipe");
        org.junit.jupiter.api.Assumptions.assumeTrue(makeFifo(pipe), "mkfifo is not available here");
        long saved = RunPlanner.readTimeoutMillis;
        RunPlanner.readTimeoutMillis = 400;
        try {
            long started = System.nanoTime();
            assertEquals(null, RunPlanner.snapshot(pipe, 100));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 4_000, "it must give up quickly");

            // and the whole plan: a signature file that is a pipe is a plain start, promptly
            Cache c = cache(Files.createDirectory(dir.resolve("app")));
            SigningKey key = SigningKey.generate();
            sign(c, key, REPOSITORY);
            Files.delete(c.cacheDir().resolve(SignatureFile.FILE_NAME));
            org.junit.jupiter.api.Assumptions.assumeTrue(makeFifo(c.cacheDir().resolve(SignatureFile.FILE_NAME)));
            started = System.nanoTime();
            assertPlainStart(c, plan(c, trusting(key)), "no usable signature");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 10, "the plan must not hang");
        } finally {
            RunPlanner.readTimeoutMillis = saved;
        }
    }

    // A writer that keeps growing and replacing the manifest while the planner runs. The manifest is read once, bounded, and that one
    // snapshot is both parsed and hashed, so the planner never reads without a bound twice and never throws. An archive is used only if
    // the bytes it actually read verified.
    @Test
    void aWriterRacingTheManifestCannotExhaustMemoryOrCrashThePlanner(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        SigningKey key = SigningKey.generate();
        sign(c, key, REPOSITORY);
        byte[] genuine = Files.readAllBytes(c.manifestFile());
        byte[] huge = new byte[(8 << 20) + 5_000_000]; // far past the limit
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread writer = new Thread(() -> {
            try {
                boolean grow = true;
                while (!stop.get()) {
                    Files.write(c.manifestFile(), grow ? huge : genuine);
                    grow = !grow;
                }
            } catch (IOException e) {
                // the test ends
            }
        });
        writer.setDaemon(true);
        writer.start();
        try {
            int used = 0;
            for (int i = 0; i < 40; i++) {
                var plan = plan(c, trusting(key)); // must neither throw nor run out of memory
                assertEquals(launch(c).size(), plan.archiveUsed() ? plan.command().size() - 1 : plan.command().size());
                if (plan.archiveUsed()) {
                    used++;
                }
            }
            assertTrue(used >= 0); // either outcome is fine; what matters is that every call returned a valid plan
        } finally {
            stop.set(true);
            writer.join(5_000);
        }
    }

    // ---- the archive is hashed from one open file, never checked and then opened ----

    @Test
    void theArchiveIsHashedOnlyIfItIsExactlyTheDeclaredSize(@TempDir Path dir) throws Exception {
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(dir.resolve("a"), content);
        assertEquals(sha256(content).substring("sha256:".length()), RunPlanner.sha256Snapshot(file, 10));
        assertEquals(null, RunPlanner.sha256Snapshot(file, 9), "longer than declared");
        assertEquals(null, RunPlanner.sha256Snapshot(file, 11), "shorter than declared");
        assertEquals(null, RunPlanner.sha256Snapshot(dir, 10), "a directory");
        assertEquals(null, RunPlanner.sha256Snapshot(dir.resolve("missing"), 10));
    }

    @Test
    void anArchiveThatIsANamedPipeIsGivenUpOnAndTheApplicationStartsPlain(@TempDir Path dir) throws Exception {
        Cache c = cache(dir);
        SigningKey key = SigningKey.generate();
        sign(c, key, REPOSITORY);
        // the path is a pipe from the start: fetch could not have written this, and opening it must never wait
        Files.delete(c.archive());
        org.junit.jupiter.api.Assumptions.assumeTrue(makeFifo(c.archive()), "mkfifo is not available here");
        long saved = RunPlanner.readTimeoutMillis;
        RunPlanner.readTimeoutMillis = 400;
        try {
            long started = System.nanoTime();
            assertEquals(null, RunPlanner.sha256Snapshot(c.archive(), Files.size(c.manifestFile()))); // any declared size: it cannot be opened
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 4, "it must give up quickly");
            var plan = plan(c, trusting(key));
            assertFalse(plan.archiveUsed(), plan.notes().toString());
            assertEquals(launch(c), plan.command());
        } finally {
            RunPlanner.readTimeoutMillis = saved;
            unblock(c.archive());
        }
    }

    /** Opens a named pipe for writing and closes it: a reader blocked opening it then gets through and ends. */
    private static void unblock(Path pipe) {
        Thread t = new Thread(() -> {
            try (var out = java.nio.channels.FileChannel.open(pipe, java.nio.file.StandardOpenOption.WRITE)) {
                // opened and closed: the reader sees end of file
            } catch (IOException e) {
                // nobody was waiting
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // a timed-out read cannot be cancelled, so a process that calls the planner again and again must not accumulate blocked threads
    @Test
    void blockedReadersAreCappedAndCanBeReleased(@TempDir Path dir) throws Exception {
        Path pipe = dir.resolve("pipe");
        org.junit.jupiter.api.Assumptions.assumeTrue(makeFifo(pipe), "mkfifo is not available here");
        Path normal = Files.write(dir.resolve("normal"), new byte[10]);
        long savedTimeout = RunPlanner.readTimeoutMillis;
        int savedCap = RunPlanner.maxBlockedReaders;
        int before = RunPlanner.liveReaders();
        RunPlanner.readTimeoutMillis = 150;
        RunPlanner.maxBlockedReaders = before + 2;
        try {
            assertEquals(null, RunPlanner.snapshot(pipe, 10));
            assertEquals(null, RunPlanner.snapshot(pipe, 10));
            assertEquals(before + 2, RunPlanner.liveReaders(), "two readers are stuck on the pipe");
            // at the cap, no further reader is started: a plain start, quickly, and no new thread
            long started = System.nanoTime();
            assertEquals(null, RunPlanner.snapshot(normal, 10), "refused at the cap even for a good file");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 100);
            assertEquals(before + 2, RunPlanner.liveReaders());
        } finally {
            RunPlanner.readTimeoutMillis = savedTimeout;
            for (int i = 0; i < 4; i++) {
                unblock(pipe); // a writer lets each stuck reader through
                Thread.sleep(150);
            }
            RunPlanner.maxBlockedReaders = savedCap;
        }
        // released: the readers ended and the count came back down
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (RunPlanner.liveReaders() > before && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(before, RunPlanner.liveReaders(), "stuck readers are released once someone opens the pipe");
        assertEquals(10, RunPlanner.snapshot(normal, 10).length, "and reads work again");
    }

    @Test
    void concurrentBlockedReadersCannotExceedTheCap(@TempDir Path dir) throws Exception {
        Path pipe = dir.resolve("contended-pipe");
        org.junit.jupiter.api.Assumptions.assumeTrue(makeFifo(pipe), "mkfifo is not available here");
        int callers = 32;
        var ready = new java.util.concurrent.CountDownLatch(callers);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(callers);
        long savedTimeout = RunPlanner.readTimeoutMillis;
        int savedCap = RunPlanner.maxBlockedReaders;
        int before = RunPlanner.liveReaders();
        RunPlanner.readTimeoutMillis = 500;
        RunPlanner.maxBlockedReaders = before + 2;
        try {
            var results = new ArrayList<java.util.concurrent.Future<byte[]>>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return RunPlanner.snapshot(pipe, 10);
                }));
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (var result : results) {
                assertEquals(null, result.get(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertEquals(before + 2, RunPlanner.liveReaders(), "only two readers may block even under contention");
        } finally {
            start.countDown();
            pool.shutdownNow();
            for (int i = 0; i < 2; i++) {
                unblock(pipe);
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (RunPlanner.liveReaders() > before && System.nanoTime() < deadline) {
                unblock(pipe);
                Thread.sleep(25);
            }
            RunPlanner.readTimeoutMillis = savedTimeout;
            RunPlanner.maxBlockedReaders = savedCap;
        }
        assertEquals(before, RunPlanner.liveReaders(), "blocked readers release their permits");
    }
}
