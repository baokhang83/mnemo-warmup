package io.github.baokhang83.mnemo.warmup.core;

import java.util.Base64;
import java.util.List;

/**
 * {@code mnemo-warmup.signature}: the signature a fetch found with an archive, kept next to it so that {@code run} can check the
 * files on disk again at every start (docs/adr/0013). It holds no statement: the checker rebuilds the statement from the files and the
 * repository named here, so the file cannot lie about what was signed. Go reads and writes the same text (launcher/signing.go).
 */
public final class SignatureFile {
    private SignatureFile() {}

    public static final String FILE_NAME = "mnemo-warmup.signature";
    static final String HEADER = "mnemo-warmup-signature-v1";

    public record Contents(ArchiveSignature signature, String repository) {}

    public static String text(ArchiveSignature signature, String repository) {
        // a SignedStatement validates the repository the same way, and keeps a newline out of the file
        new SignedStatement("sha256:" + "0".repeat(64), "sha256:" + "0".repeat(64), "sha256:" + "0".repeat(64), 1, repository);
        return HEADER + "\nkeyid: " + signature.keyId() + "\nsignature: " + signature.signatureBase64() + "\nrepository: " + repository + "\n";
    }

    /** @throws IllegalArgumentException if it is not exactly what {@link #text} writes */
    public static Contents parse(String text) {
        if (text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("not a mnemo-warmup signature file"); // Go refuses a carriage return; so must Java
        }
        List<String> lines = text.lines().toList();
        if (lines.size() != 4 || !HEADER.equals(lines.get(0)) || !text.endsWith("\n")) {
            throw new IllegalArgumentException("not a mnemo-warmup signature file");
        }
        String keyId = value(lines.get(1), "keyid");
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(value(lines.get(2), "signature"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("signature file: the signature is not valid base64");
        }
        String repository = value(lines.get(3), "repository");
        // the same checks as when it was written
        new SignedStatement("sha256:" + "0".repeat(64), "sha256:" + "0".repeat(64), "sha256:" + "0".repeat(64), 1, repository);
        return new Contents(new ArchiveSignature(keyId, signature), repository);
    }

    private static String value(String line, String name) {
        if (!line.startsWith(name + ": ")) {
            throw new IllegalArgumentException("signature file: expected a \"" + name + "\" line");
        }
        return line.substring(name.length() + 2);
    }
}
