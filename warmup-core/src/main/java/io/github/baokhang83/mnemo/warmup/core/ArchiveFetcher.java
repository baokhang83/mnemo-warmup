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
     * Removes what a previous fetch left, so a miss can never leave stale flags that point at an old
     * archive. Call it before anything that can fail.
     */
    public static void clear(Path outDir) {
        try {
            Files.deleteIfExists(outDir.resolve(FLAGS_FILE));
            Files.deleteIfExists(outDir.resolve(ArchiveManifest.FILE_NAME));
        } catch (IOException e) {
            throw new StoreException("Cannot clear stale files in " + outDir + ": " + e.getMessage(), e);
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
            Path flags = write(outDir, FLAGS_FILE, live.cacheType().runtimeFlag(hit.archiveFile().toAbsolutePath().toString()) + "\n");
            write(outDir, ArchiveManifest.FILE_NAME, hit.manifest().toJson());
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
