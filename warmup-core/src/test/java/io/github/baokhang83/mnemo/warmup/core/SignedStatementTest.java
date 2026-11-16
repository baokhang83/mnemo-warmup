package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The other half of launcher/signing_test.go. Both read launcher/testdata/signing/vector.json, which was written once by Go and is
 * frozen: Java must reproduce the same statement bytes, and the JDK's own Ed25519 must produce and accept the same signature. If a
 * test here fails, the two sides no longer agree on what is signed, and no archive signed by one would verify on the other.
 */
class SignedStatementTest {

    private static final Path VECTOR = Path.of("..", "launcher", "testdata", "signing", "vector.json");
    private static final HexFormat HEX = HexFormat.of();

    private Map<String, Object> vector;
    private SignedStatement statement;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void load() throws Exception {
        assumeTrue(Files.isRegularFile(VECTOR), "the launcher directory is not part of this checkout");
        vector = (Map<String, Object>) Json.parse(Files.readString(VECTOR, StandardCharsets.UTF_8));
        Map<String, Object> s = (Map<String, Object>) vector.get("statement");
        statement = new SignedStatement(
                (String) s.get("fingerprint"), (String) s.get("manifest"), (String) s.get("archive"),
                ((Number) s.get("size")).longValue(), (String) s.get("repository"));
    }

    @Test
    void reproducesTheFrozenText() {
        assertEquals(vector.get("text"), statement.text());
        assertArrayEquals(((String) vector.get("text")).getBytes(StandardCharsets.UTF_8), statement.bytes());
    }

    // Ed25519 signatures are deterministic, so the JDK must produce exactly the bytes Go produced.
    @Test
    void theJdkReproducesAndAcceptsTheFrozenSignature() throws Exception {
        KeyFactory keys = KeyFactory.getInstance("Ed25519");
        // PKCS#8 and X.509 wrappers around the raw 32-byte seed and public key
        PrivateKey priv = keys.generatePrivate(new PKCS8EncodedKeySpec(HEX.parseHex("302e020100300506032b657004220420" + vector.get("seed"))));
        PublicKey pub = keys.generatePublic(new X509EncodedKeySpec(HEX.parseHex("302a300506032b6570032100" + vector.get("publicKey"))));

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(priv);
        signer.update(statement.bytes());
        byte[] signature = signer.sign();
        assertEquals(vector.get("signature"), HEX.formatHex(signature), "the JDK and Go must produce the same signature");

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pub);
        verifier.update(statement.bytes());
        assertTrue(verifier.verify(HEX.parseHex((String) vector.get("signature"))));
    }

    // Changing any one field changes the signed bytes, so a signature for one archive never verifies for another (a replay).
    @Test
    void everyFieldChangesTheSignedBytesAndBreaksTheSignature() throws Exception {
        KeyFactory keys = KeyFactory.getInstance("Ed25519");
        PublicKey pub = keys.generatePublic(new X509EncodedKeySpec(HEX.parseHex("302a300506032b6570032100" + vector.get("publicKey"))));
        byte[] signature = HEX.parseHex((String) vector.get("signature"));
        String other = "sha256:" + "44".repeat(32);
        Map<String, UnaryOperator<SignedStatement>> variants = Map.of(
                "fingerprint", s -> new SignedStatement(other, s.manifest(), s.archive(), s.size(), s.repository()),
                "manifest", s -> new SignedStatement(s.fingerprint(), other, s.archive(), s.size(), s.repository()),
                "archive", s -> new SignedStatement(s.fingerprint(), s.manifest(), other, s.size(), s.repository()),
                "size", s -> new SignedStatement(s.fingerprint(), s.manifest(), s.archive(), s.size() + 1, s.repository()),
                "repository", s -> new SignedStatement(s.fingerprint(), s.manifest(), s.archive(), s.size(), "team/other"));
        for (var variant : variants.entrySet()) {
            SignedStatement changed = variant.getValue().apply(statement);
            assertNotEquals(statement.text(), changed.text(), variant.getKey());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(pub);
            verifier.update(changed.bytes());
            assertFalse(verifier.verify(signature), "a signature for the original verified after changing " + variant.getKey());
        }
    }

    @Test
    void refusesWhatIsNotAStatement() {
        String ok = "sha256:" + "11".repeat(32);
        Map<String, Runnable> bad = Map.ofEntries(
                Map.entry("short digest", () -> new SignedStatement(ok, ok, "sha256:abcd", 1, "team/app")),
                Map.entry("upper-case digest", () -> new SignedStatement(ok, "sha256:" + "AB".repeat(32), ok, 1, "team/app")),
                Map.entry("no algorithm", () -> new SignedStatement("11".repeat(32), ok, ok, 1, "team/app")),
                Map.entry("digest with a newline", () -> new SignedStatement(ok, ok, ok + "\n", 1, "team/app")),
                Map.entry("null digest", () -> new SignedStatement(null, ok, ok, 1, "team/app")),
                Map.entry("zero size", () -> new SignedStatement(ok, ok, ok, 0, "team/app")),
                Map.entry("negative size", () -> new SignedStatement(ok, ok, ok, -1, "team/app")),
                Map.entry("empty repository", () -> new SignedStatement(ok, ok, ok, 1, "")),
                Map.entry("null repository", () -> new SignedStatement(ok, ok, ok, 1, null)),
                Map.entry("newline in repository", () -> new SignedStatement(ok, ok, ok, 1, "team/app\nsize: 1")),
                Map.entry("trailing newline", () -> new SignedStatement(ok, ok, ok, 1, "team/app\n")),
                Map.entry("upper-case repository", () -> new SignedStatement(ok, ok, ok, 1, "Team/App")),
                Map.entry("host in repository", () -> new SignedStatement(ok, ok, ok, 1, "registry:5000/team/app")));
        bad.forEach((name, build) -> assertThrows(IllegalArgumentException.class, build::run, name + " was accepted"));
    }
}
