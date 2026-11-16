package io.github.baokhang83.mnemo.warmup.core;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The statement an archive signature covers (docs/adr/0013-signed-archives.md). A verifier never reads this text from the registry:
 * it builds it from what it actually observed, so a valid signature cannot be moved to another fingerprint, manifest, archive or
 * repository. The Go launcher builds the same bytes (launcher/signing.go); launcher/testdata/signing/vector.json is the frozen
 * contract both sides must reproduce.
 *
 * @param fingerprint {@code sha256:<64 hex>}: what the archive was trained for
 * @param manifest    {@code sha256:<64 hex>}: the stored manifest, which is the OCI config blob
 * @param archive     {@code sha256:<64 hex>}: the archive, which is the OCI layer
 * @param size        the archive's size in bytes
 * @param repository  the repository path as the operator configured it, without the registry host
 */
public record SignedStatement(String fingerprint, String manifest, String archive, long size, String repository) {

    public static final String HEADER = "mnemo-warmup-archive-v1";

    // matches() needs the whole string to fit, so a trailing newline is refused (it would otherwise change the framing)
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern REPOSITORY = Pattern.compile("[a-z0-9]+([._/-][a-z0-9]+)*");

    /** Refuses a field that is not what it claims to be, which also means no field can contain a newline. */
    public SignedStatement {
        requireDigest("fingerprint", fingerprint);
        requireDigest("manifest", manifest);
        requireDigest("archive", archive);
        if (size <= 0) {
            throw new IllegalArgumentException("signed statement: size must be positive");
        }
        if (repository == null || !REPOSITORY.matcher(repository).matches()) {
            throw new IllegalArgumentException("signed statement: \"" + repository + "\" is not a repository path");
        }
    }

    private static void requireDigest(String name, String value) {
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException("signed statement: " + name + " is not a sha256 digest");
        }
    }

    /** The exact text that is signed. */
    public String text() {
        return HEADER + "\n"
                + "fingerprint: " + fingerprint + "\n"
                + "manifest: " + manifest + "\n"
                + "archive: " + archive + "\n"
                + "size: " + size + "\n"
                + "repository: " + repository + "\n";
    }

    /** The bytes that are signed (UTF-8; every field is ASCII). */
    public byte[] bytes() {
        return text().getBytes(StandardCharsets.UTF_8);
    }
}
