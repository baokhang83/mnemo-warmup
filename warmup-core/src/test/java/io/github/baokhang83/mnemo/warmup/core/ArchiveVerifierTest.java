package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArchiveVerifierTest {

    private static final String D = "sha256:" + "11".repeat(32);

    private static SignedStatement statement(String repository) {
        return new SignedStatement(D, "sha256:" + "22".repeat(32), "sha256:" + "33".repeat(32), 4096, repository);
    }

    private static ArchiveVerifier verifierFor(List<String> notes, SigningKey... keys) {
        StringBuilder entries = new StringBuilder();
        for (SigningKey k : keys) {
            entries.append(entries.length() == 0 ? "" : ",").append(k.publicKeyEntry());
        }
        return ArchiveVerifier.fromEntries(entries.toString(), notes::add);
    }

    @Test
    void aSignatureFromAListedKeyIsValid() {
        SigningKey key = SigningKey.generate();
        List<String> notes = new ArrayList<>();
        var verifier = verifierFor(notes, key);
        assertEquals(ArchiveVerifier.Result.VALID, verifier.verify(statement("team/app"), key.sign(statement("team/app"))));
        assertTrue(notes.isEmpty());
        assertFalse(verifier.isEmpty());
    }

    // the replay case from the design: a signature made for team/billing must not verify for team/orders
    @Test
    void aSignatureForAnotherRepositoryDoesNotVerify() {
        SigningKey key = SigningKey.generate();
        var verifier = verifierFor(new ArrayList<>(), key);
        ArchiveSignature forBilling = key.sign(statement("team/billing"));
        assertEquals(ArchiveVerifier.Result.VALID, verifier.verify(statement("team/billing"), forBilling));
        assertEquals(ArchiveVerifier.Result.BAD_SIGNATURE, verifier.verify(statement("team/orders"), forBilling));
    }

    @Test
    void aSignatureFromAKeyThatIsNotListedIsUnknown() {
        SigningKey trusted = SigningKey.generate();
        SigningKey stranger = SigningKey.generate();
        var verifier = verifierFor(new ArrayList<>(), trusted);
        assertEquals(ArchiveVerifier.Result.UNKNOWN_KEY, verifier.verify(statement("team/app"), stranger.sign(statement("team/app"))));
    }

    // a forged key id cannot make an unlisted key's signature pass: the listed key must verify it
    @Test
    void aStrangersSignatureUnderATrustedKeyIdIsBad() {
        SigningKey trusted = SigningKey.generate();
        SigningKey stranger = SigningKey.generate();
        var verifier = verifierFor(new ArrayList<>(), trusted);
        ArchiveSignature forged = new ArchiveSignature(trusted.keyId(), stranger.sign(statement("team/app")).signature());
        assertEquals(ArchiveVerifier.Result.BAD_SIGNATURE, verifier.verify(statement("team/app"), forged));
    }

    @Test
    void aFlippedBitIsBad() {
        SigningKey key = SigningKey.generate();
        var verifier = verifierFor(new ArrayList<>(), key);
        byte[] sig = key.sign(statement("team/app")).signature();
        sig[10] ^= 1;
        assertEquals(ArchiveVerifier.Result.BAD_SIGNATURE, verifier.verify(statement("team/app"), new ArchiveSignature(key.keyId(), sig)));
    }

    // rotation: pods list the old and the new public key, and either one's signatures are accepted
    @Test
    void severalKeysCanBeListedForRotation() {
        SigningKey oldKey = SigningKey.generate();
        SigningKey newKey = SigningKey.generate();
        var verifier = verifierFor(new ArrayList<>(), oldKey, newKey);
        assertEquals(ArchiveVerifier.Result.VALID, verifier.verify(statement("team/app"), oldKey.sign(statement("team/app"))));
        assertEquals(ArchiveVerifier.Result.VALID, verifier.verify(statement("team/app"), newKey.sign(statement("team/app"))));
    }

    @Test
    void badEntriesAreSkippedWithANoteAndNeverTrusted() {
        SigningKey key = SigningKey.generate();
        SigningKey other = SigningKey.generate();
        List<String> notes = new ArrayList<>();
        String entries = String.join(",",
                "justtext",
                "abcd=!!!notbase64",
                "abcd=" + java.util.Base64.getEncoder().encodeToString(new byte[31]),
                "0000000000000000=" + java.util.Base64.getEncoder().encodeToString(other.publicKey()), // an id that is not that key's id
                "",
                key.publicKeyEntry());
        var verifier = ArchiveVerifier.fromEntries(entries, notes::add);
        assertEquals(4, notes.size(), notes.toString());
        // only the good entry survived: the mislabelled key is not trusted
        assertEquals(ArchiveVerifier.Result.VALID, verifier.verify(statement("team/app"), key.sign(statement("team/app"))));
        assertEquals(ArchiveVerifier.Result.UNKNOWN_KEY, verifier.verify(statement("team/app"), other.sign(statement("team/app"))));
    }

    @Test
    void noEntriesMeansNoVerifier() {
        assertTrue(ArchiveVerifier.fromEntries(null, n -> { }).isEmpty());
        assertTrue(ArchiveVerifier.fromEntries("", n -> { }).isEmpty());
        assertTrue(ArchiveVerifier.fromEntries(" , ", n -> { }).isEmpty());
    }

    @Test
    void signaturesHaveTheRightShape() {
        assertThrows(IllegalArgumentException.class, () -> new ArchiveSignature("short", new byte[64]));
        assertThrows(IllegalArgumentException.class, () -> new ArchiveSignature("0".repeat(16), new byte[63]));
        assertThrows(IllegalArgumentException.class, () -> new ArchiveSignature("0".repeat(16), null));
    }
}
