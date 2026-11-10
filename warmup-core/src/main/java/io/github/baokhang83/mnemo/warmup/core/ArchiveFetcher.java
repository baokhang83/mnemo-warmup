package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The init-container side of the store: looks the live environment up, in order of preference, and
 * leaves the first verified archive, its manifest and a flags file in an output directory. A pod
 * with no usable archive gets an output directory without a flags file and starts uncached
 * (constitution §1). It never throws for a store problem: those become notes on the result.
 */
public final class ArchiveFetcher {

    /** One line of JVM options that make the JVM use the archive; empty or absent means "run plain". */
    public static final String FLAGS_FILE = "mnemo-warmup.flags";

    public enum Outcome {
        HIT,
        MISS
    }

    /**
     * @param archive   the downloaded archive, or null on a miss
     * @param flagsFile the written flags file, or null on a miss
     * @param notes     why each earlier candidate was not used, for the log
     */
    public record Result(
            Outcome outcome, String fingerprint, CacheStrategy cacheType, Path archive, Path flagsFile, List<String> notes) {}

    /**
     * Withdraws what a previous fetch published, so a miss can never leave flags that point at an old
     * archive. The flags file is the only thing the JVM launch reads, so it goes first: if it cannot
     * be deleted it is emptied, and an empty flags file means "run plain". If neither works the
     * exception says so, and the caller must treat the output directory as unreliable (the consumer's
     * own manifest check, and the JVM's refusal of a mismatched archive, are then the only guard).
     */
    public static void clear(Path outDir) {
        Path flags = outDir.resolve(FLAGS_FILE);
        try {
            Files.deleteIfExists(flags);
        } catch (IOException deleteFailure) {
            try {
                Files.writeString(flags, "", StandardCharsets.UTF_8);
            } catch (IOException truncateFailure) {
                throw new StoreException(
                        "Cannot remove or empty the stale " + flags + " (" + deleteFailure.getMessage()
                                + "); an old archive may still be referenced",
                        deleteFailure);
            }
        }
        try {
            Files.deleteIfExists(outDir.resolve(ArchiveManifest.FILE_NAME));
        } catch (IOException e) {
            throw new StoreException("Cannot remove the stale manifest in " + outDir + ": " + e.getMessage(), e);
        }
    }

    /**
     * @param candidates the live manifests to look for, most preferred first (one per cache type)
     */
    public Result fetch(ArchiveStore store, List<ArchiveManifest> candidates, Path outDir) {
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new StoreException("Cannot create " + outDir + ": " + e.getMessage(), e);
        }
        clear(outDir);
        try {
            return lookUp(store, candidates, outDir);
        } catch (RuntimeException e) {
            // Whatever failed, never leave a flags file behind that the app would act on.
            try {
                clear(outDir);
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private Result lookUp(ArchiveStore store, List<ArchiveManifest> candidates, Path outDir) {
        List<String> notes = new ArrayList<>();
        for (ArchiveManifest live : candidates) {
            String fingerprint = live.fingerprint();
            Optional<ArchiveStore.StoredArchive> stored;
            try {
                stored = store.get(fingerprint, outDir);
            } catch (StoreException e) {
                notes.add(live.cacheType() + ": " + e.getMessage());
                continue;
            }
            if (stored.isEmpty()) {
                notes.add(live.cacheType() + ": no archive stored for " + fingerprint);
                continue;
            }
            ArchiveStore.StoredArchive hit = stored.get();
            List<String> problems = hit.manifest().mismatches(live);
            if (!problems.isEmpty()) {
                deleteQuietly(hit.archiveFile());
                notes.add(live.cacheType() + ": stored manifest does not match this environment: " + problems);
                continue;
            }
            // The flags file is the commit point: it is written last, so every other file is in place
            // before anything can act on it.
            write(outDir, ArchiveManifest.FILE_NAME, hit.manifest().toJson());
            Path flags = write(outDir, FLAGS_FILE, live.cacheType().runtimeFlag(hit.archiveFile().toAbsolutePath().toString()) + "\n");
            return new Result(Outcome.HIT, fingerprint, live.cacheType(), hit.archiveFile(), flags, notes);
        }
        return new Result(Outcome.MISS, null, null, null, null, notes);
    }

    private static Path write(Path dir, String name, String content) {
        try {
            Path tmp = Files.createTempFile(dir, name, ".part");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Path target = dir.resolve(name);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            throw new StoreException("Cannot write " + name + " in " + dir + ": " + e.getMessage(), e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort; without a flags file the file is never used
        }
    }
}
