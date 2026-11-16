package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * An Ed25519 key pair that signs archives (docs/adr/0013). The JDK can sign with a private key but cannot derive its public key,
 * so the key file stores both, and loading checks they belong together with a sign-and-verify round trip. Only the platform's own
 * Ed25519 is used (constitution §4).
 */
public final class SigningKey {

    /** The environment variable that names the key file the trainer signs with. */
    public static final String ENV_KEY_FILE = "MNEMO_WARMUP_SIGNING_KEY_FILE";
    static final String FILE_HEADER = "mnemo-warmup-signing-key-v1";
    // the fixed wrappers (PKCS#8 and X.509) that put a raw 32-byte Ed25519 seed or public key into the encodings the JDK reads
    private static final byte[] PKCS8_PREFIX = HexFormat.of().parseHex("302e020100300506032b657004220420");
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private final byte[] seed;
    private final byte[] publicKey;
    private final PrivateKey privateKey;

    private SigningKey(byte[] seed, byte[] publicKey) {
        this.seed = seed.clone();
        this.publicKey = publicKey.clone();
        this.privateKey = privateKeyFrom(seed);
    }

    /** A new random key pair. */
    public static SigningKey generate() {
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] seed = Arrays.copyOfRange(pair.getPrivate().getEncoded(), PKCS8_PREFIX.length, PKCS8_PREFIX.length + 32);
            byte[] publicKey = Arrays.copyOfRange(pair.getPublic().getEncoded(), X509_PREFIX.length, X509_PREFIX.length + 32);
            return new SigningKey(seed, publicKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available in this JDK", e);
        }
    }

    /** Builds a key from its two halves and refuses a pair that does not belong together. */
    public static SigningKey of(byte[] seed, byte[] publicKey) {
        if (seed == null || seed.length != 32 || publicKey == null || publicKey.length != 32) {
            throw new IllegalArgumentException("an Ed25519 seed and public key are 32 bytes each");
        }
        String weak = weakness(publicKey);
        if (weak != null) {
            throw new IllegalArgumentException("the public key is not usable: " + weak);
        }
        SigningKey key = new SigningKey(seed, publicKey);
        byte[] probe = "mnemo-warmup signing key self-check".getBytes(StandardCharsets.UTF_8);
        if (!ArchiveVerifier.verifies(publicKey, probe, key.signRaw(probe))) {
            throw new IllegalArgumentException("the public key does not belong to the private key");
        }
        return key;
    }

    /** Reads a key file written by {@link #fileText()}. */
    public static SigningKey fromFile(Path file) {
        try {
            return fromText(Files.readString(file, StandardCharsets.UTF_8));
        } catch (java.nio.file.AccessDeniedException e) {
            // the most likely cause in a cluster: the Secret file is owned by root and readable by its group (mode 0440), and the pod has no
            // fsGroup that puts the trainer's user in that group
            throw new IllegalArgumentException("cannot read the signing key " + file + ": permission denied. The key file is readable by its "
                    + "owner and group only; when the trainer runs as a non-root user, set fsGroup in the pod's securityContext so that user "
                    + "is in the file's group (docs/configuration.md, Signed archives)", e);
        } catch (java.nio.file.NoSuchFileException e) {
            throw new IllegalArgumentException("the signing key " + file + " does not exist; is the Secret mounted?", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read the signing key " + file + ": " + e.getMessage(), e);
        }
    }

    static SigningKey fromText(String text) {
        if (text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("not a mnemo-warmup signing key file");
        }
        List<String> lines = text.lines().toList();
        if (lines.size() != 4 || !FILE_HEADER.equals(lines.get(0))) {
            throw new IllegalArgumentException("not a mnemo-warmup signing key file");
        }
        byte[] publicKey = decode(field(lines.get(2), "public"));
        byte[] seed = decode(field(lines.get(3), "seed"));
        SigningKey key = of(seed, publicKey);
        if (!key.keyId().equals(field(lines.get(1), "keyid"))) {
            throw new IllegalArgumentException("the key id in the file does not match its public key");
        }
        return key;
    }

    private static String field(String line, String name) {
        if (!line.startsWith(name + ": ")) {
            throw new IllegalArgumentException("signing key file: expected a \"" + name + "\" line");
        }
        return line.substring(name.length() + 2);
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("signing key file: not valid base64");
        }
    }

    /** The file contents: a header and one {@code name: value} line each for the id, the public key and the seed. */
    public String fileText() {
        Base64.Encoder b64 = Base64.getEncoder();
        return FILE_HEADER + "\nkeyid: " + keyId() + "\npublic: " + b64.encodeToString(publicKey) + "\nseed: " + b64.encodeToString(seed) + "\n";
    }

    // The y-coordinates (little-endian) of the Ed25519 points of order 1, 2, 4 and 8. A public key that is one of them makes a trivial
    // signature verify for every message, so no honest key looks like this. They were computed from the curve equation; the Go launcher
    // refuses the same set (launcher/signing.go) and launcher/testdata/signing/vector.json lists the encodings both must refuse.
    private static final List<byte[]> SMALL_ORDER_Y = List.of(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
            "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a")
            .stream().map(h -> HexFormat.of().parseHex(h)).toList();

    /**
     * Why a public key must never be trusted, or null when it may be. The top bit of the last byte is only the sign of x, so it is
     * ignored; a y at or above p = 2^255 - 19 is a second spelling of a smaller y, which the JDK and Go read differently.
     */
    public static String weakness(byte[] publicKey) {
        if (publicKey == null || publicKey.length != 32) {
            return "an Ed25519 public key is 32 bytes";
        }
        byte[] y = publicKey.clone();
        y[31] &= 0x7f;
        boolean allFf = true;
        for (int i = 1; i < 31; i++) {
            allFf &= y[i] == (byte) 0xff;
        }
        if (y[31] == 0x7f && allFf && (y[0] & 0xff) >= 0xed) {
            return "its encoding is not canonical";
        }
        for (byte[] bad : SMALL_ORDER_Y) {
            if (Arrays.equals(y, bad)) {
                return "it is a small-order point, for which any message verifies";
            }
        }
        return null;
    }

    /** The first 8 bytes of the SHA-256 of the raw public key, as 16 lower-case hex characters. */
    public static String keyId(byte[] publicKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey), 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String keyId() {
        return keyId(publicKey);
    }

    public byte[] publicKey() {
        return publicKey.clone();
    }

    /** What operators give to pods: {@code <key id>=<base64 public key>}. */
    public String publicKeyEntry() {
        return ArchiveVerifier.publicKeyEntry(publicKey);
    }

    public ArchiveSignature sign(SignedStatement statement) {
        return new ArchiveSignature(keyId(), signRaw(statement.bytes()));
    }

    private byte[] signRaw(byte[] message) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(message);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign: " + e.getMessage(), e);
        }
    }

    private static PrivateKey privateKeyFrom(byte[] seed) {
        try {
            byte[] encoded = new byte[PKCS8_PREFIX.length + 32];
            System.arraycopy(PKCS8_PREFIX, 0, encoded, 0, PKCS8_PREFIX.length);
            System.arraycopy(seed, 0, encoded, PKCS8_PREFIX.length, 32);
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not a usable Ed25519 seed", e);
        }
    }

    /** The JDK public key for 32 raw bytes. */
    static PublicKey publicKeyFrom(byte[] raw) throws GeneralSecurityException {
        byte[] encoded = new byte[X509_PREFIX.length + 32];
        System.arraycopy(X509_PREFIX, 0, encoded, 0, X509_PREFIX.length);
        System.arraycopy(raw, 0, encoded, X509_PREFIX.length, 32);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    }

    @Override
    public String toString() {
        return "SigningKey[" + keyId() + "]"; // never the seed
    }
}
