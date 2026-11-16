package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warmup.core.OciArchiveStore;
import io.github.baokhang83.mnemo.warmup.core.SigningKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How the environment turns signing on (docs/adr/0013). These assert what the store now does (it requires signatures, it signs with a
 * given key), not that the same object came back, so a wiring method that quietly does nothing fails here. The behaviour itself is tested
 * against a registry in the core module.
 */
class SigningWiringTest {

    @TempDir
    Path tmp;

    private static OciArchiveStore store() {
        return OciArchiveStore.of("oci://127.0.0.1:1/team/app", true, null, Duration.ofSeconds(1));
    }

    private Path keyFile(SigningKey key) throws Exception {
        Path file = Files.createTempFile(tmp, "k", ".key");
        Files.writeString(file, key.fileText());
        return file;
    }

    // ---- the pull side ----

    @Test
    void withoutTheVariableNothingIsRequired() {
        var s = Main.withSignatureCheck(store(), Map.of(), n -> { });
        assertFalse(s.requiresSignatures());
    }

    @Test
    void aListRequiresSignatures() {
        List<String> notes = new ArrayList<>();
        var s = Main.withSignatureCheck(store(), Map.of(Main.ENV_SIGNING_PUBLIC_KEYS, SigningKey.generate().publicKeyEntry()), notes::add);
        assertTrue(s.requiresSignatures());
        assertTrue(notes.isEmpty(), notes.toString());
    }

    // an empty value from a template, or a typo, must never switch the check off: it is still a list, and it trusts nothing
    @Test
    void aPresentButBlankOrUnusableListStillRequiresSignaturesAndSaysThatNothingIsTrusted() {
        for (String blank : new String[] {"", "   ", ",", "garbage", "abcd=!!!", "justtext,abcd=!!!"}) {
            List<String> notes = new ArrayList<>();
            var s = Main.withSignatureCheck(store(), Map.of(Main.ENV_SIGNING_PUBLIC_KEYS, blank), notes::add);
            assertTrue(s.requiresSignatures(), "'" + blank + "' must not switch the check off");
            assertTrue(notes.stream().anyMatch(n -> n.contains("lists no usable key")), notes.toString());
        }
    }

    @Test
    void aWeakKeyInTheListIsReportedAndNotTrusted() {
        byte[] identity = new byte[32];
        identity[0] = 1; // the identity point: a trivial signature would verify for any message
        List<String> notes = new ArrayList<>();
        var s = Main.withSignatureCheck(store(), Map.of(Main.ENV_SIGNING_PUBLIC_KEYS,
                io.github.baokhang83.mnemo.warmup.core.ArchiveVerifier.publicKeyEntry(identity)), notes::add);
        assertTrue(s.requiresSignatures());
        assertTrue(notes.stream().anyMatch(n -> n.contains("small-order")), notes.toString());
    }

    // ---- the publish side ----

    @Test
    void withoutAKeyFileNothingIsSigned() {
        assertNull(Main.withSigning(store(), Map.of(), true).signingKeyId());
    }

    @Test
    void aKeyFileMakesTheStoreSignWithThatKey() throws Exception {
        SigningKey key = SigningKey.generate();
        var s = Main.withSigning(store(), Map.of(Main.ENV_SIGNING_KEY_FILE, keyFile(key).toString()), false);
        assertEquals(key.keyId(), s.signingKeyId());
        assertFalse(s.requiresSignatures(), "push only signs");
    }

    @Test
    void theTrainerChecksAgainstItsOwnKeyWhenThereIsNoList() throws Exception {
        SigningKey key = SigningKey.generate();
        var s = Main.withSigning(store(), Map.of(Main.ENV_SIGNING_KEY_FILE, keyFile(key).toString()), true);
        assertEquals(key.keyId(), s.signingKeyId());
        assertTrue(s.requiresSignatures(), "an entry this key did not sign is incomplete");
    }

    // asked to sign, the trainer must refuse to publish unsigned, whatever is wrong with the key
    @Test
    void anUnusableKeyFileIsAnErrorNotAnUnsignedPublish() throws Exception {
        Path junk = tmp.resolve("junk");
        Files.writeString(junk, "not a key");
        for (String bad : new String[] {tmp.resolve("missing").toString(), junk.toString(), "", "   "}) {
            for (boolean trainer : new boolean[] {true, false}) {
                var ex = assertThrows(IllegalArgumentException.class, () -> Main.withSigning(store(), Map.of(Main.ENV_SIGNING_KEY_FILE, bad), trainer), "'" + bad + "'");
                assertTrue(ex.getMessage().contains("signing key") || ex.getMessage().contains("empty"), ex.getMessage());
            }
        }
    }

    // a key that is not on the pods' list would publish archives every pod refuses
    @Test
    void theTrainerRefusesToSignWithAKeyThePodsDoNotTrust() throws Exception {
        SigningKey mine = SigningKey.generate();
        SigningKey other = SigningKey.generate();
        var ex = assertThrows(IllegalArgumentException.class, () -> Main.withSigning(store(),
                Map.of(Main.ENV_SIGNING_KEY_FILE, keyFile(mine).toString(), Main.ENV_SIGNING_PUBLIC_KEYS, other.publicKeyEntry()), true));
        assertTrue(ex.getMessage().contains(mine.keyId()) && ex.getMessage().contains("refuse"), ex.getMessage());
        // an unusable list does not make the key trusted either
        assertThrows(IllegalArgumentException.class, () -> Main.withSigning(store(),
                Map.of(Main.ENV_SIGNING_KEY_FILE, keyFile(mine).toString(), Main.ENV_SIGNING_PUBLIC_KEYS, ""), true));
        // and with the key on the list (alone or with others) it signs
        var ok = Main.withSigning(store(), Map.of(Main.ENV_SIGNING_KEY_FILE, keyFile(mine).toString(),
                Main.ENV_SIGNING_PUBLIC_KEYS, other.publicKeyEntry() + "," + mine.publicKeyEntry()), true);
        assertEquals(mine.keyId(), ok.signingKeyId());
        assertTrue(ok.requiresSignatures());
    }

    @Test
    void anUnreadableKeyFileSaysWhyAndHowToFixIt() throws Exception {
        Path locked = keyFile(SigningKey.generate());
        try {
            Files.setPosixFilePermissions(locked, java.util.Set.of());
        } catch (UnsupportedOperationException notPosix) {
            return;
        }
        if (Files.isReadable(locked)) {
            return; // running as a user for whom permissions do not apply (root): nothing to show
        }
        var ex = assertThrows(IllegalArgumentException.class, () -> SigningKey.fromFile(locked));
        assertTrue(ex.getMessage().contains("permission denied") && ex.getMessage().contains("fsGroup"), ex.getMessage());
    }
}
