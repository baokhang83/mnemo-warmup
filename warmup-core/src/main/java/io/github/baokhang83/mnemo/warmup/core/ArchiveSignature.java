package io.github.baokhang83.mnemo.warmup.core;

import java.util.Base64;

/**
 * A signature over a {@link SignedStatement} and the id of the key that made it, as stored with an archive (docs/adr/0013).
 *
 * @param keyId     16 hex characters, see {@link SigningKey#keyId(byte[])}
 * @param signature the 64 raw bytes of an Ed25519 signature
 */
public record ArchiveSignature(String keyId, byte[] signature) {

    public ArchiveSignature {
        if (keyId == null || !keyId.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("key id must be 16 lower-case hex characters");
        }
        if (signature == null || signature.length != 64) {
            throw new IllegalArgumentException("an Ed25519 signature is 64 bytes");
        }
        signature = signature.clone();
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }

    public String signatureBase64() {
        return Base64.getEncoder().encodeToString(signature);
    }
}
