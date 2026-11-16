package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SigningKeyTest {

    private static final Path VECTOR = Path.of("..", "launcher", "testdata", "signing", "vector.json");
    private static final HexFormat HEX = HexFormat.of();

    @TempDir
    Path tmp;

    private Map<String, Object> vector;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void load() throws Exception {
        assumeTrue(Files.isRegularFile(VECTOR), "the launcher directory is not part of this checkout");
        vector = (Map<String, Object>) Json.parse(Files.readString(VECTOR, StandardCharsets.UTF_8));
    }

    private SignedStatement statement() {
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) vector.get("statement");
        return new SignedStatement((String) s.get("fingerprint"), (String) s.get("manifest"), (String) s.get("archive"),
                ((Number) s.get("size")).longValue(), (String) s.get("repository"));
    }

    private SigningKey vectorKey() {
        return SigningKey.of(HEX.parseHex((String) vector.get("seed")), HEX.parseHex((String) vector.get("publicKey")));
    }

    // the id and the public-key entry are part of the cross-language contract: Go must derive the same ones
    @Test
    void theKeyIdAndEntryMatchTheFrozenVector() {
        SigningKey key = vectorKey();
        assertEquals(vector.get("keyId"), key.keyId());
        assertEquals(vector.get("publicKeyEntry"), key.publicKeyEntry());
        assertEquals(vector.get("publicKeyBase64"), Base64.getEncoder().encodeToString(key.publicKey()));
        assertEquals(vector.get("seedBase64"), Base64.getEncoder().encodeToString(HEX.parseHex((String) vector.get("seed"))));
    }

    @Test
    void signingTheFrozenStatementGivesTheFrozenSignature() {
        ArchiveSignature signature = vectorKey().sign(statement());
        assertEquals(vector.get("keyId"), signature.keyId());
        assertEquals(vector.get("signature"), HEX.formatHex(signature.signature()));
    }

    @Test
    void aGeneratedKeyRoundTripsThroughItsFile() throws Exception {
        SigningKey key = SigningKey.generate();
        Path file = tmp.resolve("k");
        Files.writeString(file, key.fileText());
        SigningKey loaded = SigningKey.fromFile(file);
        assertEquals(key.keyId(), loaded.keyId());
        assertArrayEquals(key.publicKey(), loaded.publicKey());
        SignedStatement s = statement();
        assertArrayEquals(key.sign(s).signature(), loaded.sign(s).signature(), "Ed25519 is deterministic");
        assertTrue(key.keyId().matches("[0-9a-f]{16}"));
        assertNotEquals(key.keyId(), SigningKey.generate().keyId());
    }

    @Test
    void theSeedIsNeverInThePrintedForm() {
        SigningKey key = SigningKey.generate();
        String seed = Base64.getEncoder().encodeToString(Base64.getDecoder().decode(key.fileText().lines().toList().get(3).substring("seed: ".length())));
        assertFalse(key.toString().contains(seed));
        assertTrue(key.toString().contains(key.keyId()));
    }

    @Test
    void refusesAKeyWhosePublicHalfDoesNotBelongToTheSeed() {
        SigningKey a = SigningKey.generate();
        SigningKey b = SigningKey.generate();
        var text = a.fileText().lines().toList();
        String mixed = text.get(0) + "\nkeyid: " + b.keyId() + "\npublic: " + Base64.getEncoder().encodeToString(b.publicKey()) + "\n" + text.get(3) + "\n";
        var ex = assertThrows(IllegalArgumentException.class, () -> SigningKey.fromText(mixed));
        assertTrue(ex.getMessage().contains("does not belong"), ex.getMessage());
    }

    @Test
    void refusesAFileWhoseIdIsNotTheIdOfItsKey() {
        SigningKey key = SigningKey.generate();
        String tampered = key.fileText().replace("keyid: " + key.keyId(), "keyid: " + "0".repeat(16));
        assertThrows(IllegalArgumentException.class, () -> SigningKey.fromText(tampered));
    }

    @Test
    void refusesMalformedFiles() {
        String good = SigningKey.generate().fileText();
        for (String bad : new String[] {
                "", "hello\n", good.replace("mnemo-warmup-signing-key-v1", "other"), good.replaceFirst("seed: .*", "seed: not base64!"),
                good.replaceFirst("seed: .*", "seed: " + Base64.getEncoder().encodeToString(new byte[31])),
                good.replaceFirst("public: .*", "public: " + Base64.getEncoder().encodeToString(new byte[33])),
                good + "extra: line\n", good.replace("public: ", "pub: ")}) {
            assertThrows(IllegalArgumentException.class, () -> SigningKey.fromText(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> SigningKey.fromFile(tmp.resolve("missing")));
    }

    // no honest key looks like these: both languages must refuse them (the Go twin is launcher/signing_test.go)
    @SuppressWarnings("unchecked")
    @Test
    void theWeakEncodingsInTheSharedVectorAreNeverUsable() {
        List<String> weak = (List<String>) vector.get("weakPublicKeys");
        assertTrue(weak.size() >= 10, "the vector must list the weak encodings");
        for (String hex : weak) {
            byte[] key = HEX.parseHex(hex);
            assertNotNull(SigningKey.weakness(key), hex + " must be recognised as weak");
            var notes = new java.util.ArrayList<String>();
            var verifier = ArchiveVerifier.fromEntries(SigningKey.keyId(key) + "=" + Base64.getEncoder().encodeToString(key), notes::add);
            assertTrue(verifier.isEmpty() && notes.size() == 1, hex + " was trusted or not reported: " + notes);
            assertThrows(IllegalArgumentException.class, () -> SigningKey.of(HEX.parseHex((String) vector.get("seed")), key), hex);
        }
        assertNull(SigningKey.weakness(HEX.parseHex((String) vector.get("publicKey"))), "the vector's own key is honest");
        assertNotNull(SigningKey.weakness(new byte[31]));
    }

    // the attack: with the identity point trusted, a trivial signature verified for any message. It must never be trusted.
    @Test
    void aTrivialSignatureNeverVerifiesBecauseTheIdentityKeyIsNeverTrusted() {
        byte[] identity = new byte[32];
        identity[0] = 1;
        byte[] trivial = new byte[64];
        trivial[0] = 1; // R = identity, S = 0
        String fp = "sha256:" + "11".repeat(32);
        var verifier = ArchiveVerifier.fromEntries(SigningKey.keyId(identity) + "=" + Base64.getEncoder().encodeToString(identity), n -> { });
        var statement = new SignedStatement(fp, fp, fp, 100, "team/app");
        assertEquals(ArchiveVerifier.Result.UNKNOWN_KEY, verifier.verify(statement, new ArchiveSignature(SigningKey.keyId(identity), trivial)));
    }

    @Test
    void aKeyFileWithCarriageReturnsIsRefusedLikeGoRefusesIt() {
        String crlf = SigningKey.generate().fileText().replace("\n", "\r\n");
        assertThrows(IllegalArgumentException.class, () -> SigningKey.fromText(crlf));
    }
}
