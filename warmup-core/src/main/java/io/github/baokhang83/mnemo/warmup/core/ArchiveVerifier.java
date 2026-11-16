package io.github.baokhang83.mnemo.warmup.core;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Checks archive signatures against the public keys an operator listed (docs/adr/0013). A statement is rebuilt by the caller from
 * what it actually observed; this class only answers whether a listed key signed exactly that.
 */
public final class ArchiveVerifier {

    public enum Result {
        /** A listed key signed exactly this statement. */
        VALID,
        /** The signature names a key that is not listed. */
        UNKNOWN_KEY,
        /** The key is listed but the signature does not match the statement. */
        BAD_SIGNATURE
    }

    /** The environment variable that lists the public keys a pod trusts, as {@code id=base64,id=base64}. Setting it makes a valid signature mandatory. */
    public static final String ENV_PUBLIC_KEYS = "MNEMO_WARMUP_SIGNING_PUBLIC_KEYS";

    private final Map<String, byte[]> keys;

    private ArchiveVerifier(Map<String, byte[]> keys) {
        this.keys = Map.copyOf(keys);
    }

    /**
     * Reads {@code id=base64,id=base64}. An entry that is malformed, or whose id is not the id of its own key, is skipped with a note:
     * a typo must never silently trust the wrong key, and must never stop an application.
     */
    public static ArchiveVerifier fromEntries(String entries, Consumer<String> note) {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        if (entries != null) {
            for (String entry : entries.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    note.accept("ignoring a signing public key entry that is not id=base64");
                    continue;
                }
                String id = trimmed.substring(0, eq);
                byte[] publicKey;
                try {
                    publicKey = Base64.getDecoder().decode(trimmed.substring(eq + 1));
                } catch (IllegalArgumentException e) {
                    note.accept("ignoring signing public key " + id + ": not valid base64");
                    continue;
                }
                String weak = SigningKey.weakness(publicKey);
                if (weak != null) {
                    note.accept("ignoring signing public key " + id + ": " + weak);
                } else if (!SigningKey.keyId(publicKey).equals(id)) {
                    note.accept("ignoring signing public key " + id + ": the id is not the id of that key (expected " + SigningKey.keyId(publicKey) + ")");
                } else {
                    keys.put(id, publicKey);
                }
            }
        }
        return new ArchiveVerifier(keys);
    }

    /** {@code <key id>=<base64 public key>} for 32 raw bytes. */
    public static String publicKeyEntry(byte[] publicKey) {
        return SigningKey.keyId(publicKey) + "=" + Base64.getEncoder().encodeToString(publicKey);
    }

    /** No usable key. Handed to a store as its verifier, this refuses every archive (the key list was given, but nothing in it can be trusted). */
    public boolean isEmpty() {
        return keys.isEmpty();
    }

    /** Whether a key with this id is listed. */
    public boolean trusts(String keyId) {
        return keys.containsKey(keyId);
    }

    public Result verify(SignedStatement statement, ArchiveSignature signature) {
        byte[] publicKey = keys.get(signature.keyId());
        if (publicKey == null) {
            return Result.UNKNOWN_KEY;
        }
        return verifies(publicKey, statement.bytes(), signature.signature()) ? Result.VALID : Result.BAD_SIGNATURE;
    }

    static boolean verifies(byte[] publicKey, byte[] message, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(SigningKey.publicKeyFrom(publicKey));
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false; // a malformed signature is simply not valid
        }
    }
}
