package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

/** How the environment turns signing on (docs/adr/0013). The behaviour itself is tested against a registry in the core module. */
class SigningWiringTest {

    @TempDir
    Path tmp;

    private static OciArchiveStore store() {
        return OciArchiveStore.of("oci://127.0.0.1:1/team/app", true, null, Duration.ofSeconds(1));
    }

    @Test
    void withoutTheVariablesNothingChanges() {
        var s = store();
        assertSame(s, Main.withSignatureCheck(s, Map.of(), n -> { }));
        assertSame(s, Main.withSignatureCheck(s, Map.of(Main.ENV_SIGNING_PUBLIC_KEYS, "  "), n -> { }));
        assertSame(s, Main.withSigning(s, Map.of(), true));
        assertSame(s, Main.withSigning(s, Map.of(Main.ENV_SIGNING_KEY_FILE, ""), true));
    }

    @Test
    void badPublicKeyEntriesAreReportedAndNeverStopTheApplication() {
        List<String> notes = new ArrayList<>();
        var s = store();
        assertSame(s, Main.withSignatureCheck(s, Map.of(Main.ENV_SIGNING_PUBLIC_KEYS, "garbage,abcd=!!!"), notes::add));
        assertEquals(2, notes.size(), notes.toString());
    }

    @Test
    void aGoodPublicKeyListProducesNoNote() {
        List<String> notes = new ArrayList<>();
        Main.withSignatureCheck(store(), Map.of(Main.ENV_SIGNING_PUBLIC_KEYS, SigningKey.generate().publicKeyEntry()), notes::add);
        assertTrue(notes.isEmpty(), notes.toString());
    }

    // asked to sign, the trainer must refuse to publish rather than quietly publish unsigned
    @Test
    void anUnusableKeyFileIsAnErrorNotAnUnsignedPublish() throws Exception {
        Path missing = tmp.resolve("missing");
        assertThrows(IllegalArgumentException.class, () -> Main.withSigning(store(), Map.of(Main.ENV_SIGNING_KEY_FILE, missing.toString()), true));
        Path junk = tmp.resolve("junk");
        Files.writeString(junk, "not a key");
        var ex = assertThrows(IllegalArgumentException.class, () -> Main.withSigning(store(), Map.of(Main.ENV_SIGNING_KEY_FILE, junk.toString()), false));
        assertTrue(ex.getMessage().contains("signing key"), ex.getMessage());
    }

    @Test
    void aGoodKeyFileIsAccepted() throws Exception {
        Path file = tmp.resolve("k");
        Files.writeString(file, SigningKey.generate().fileText());
        var s = store();
        assertSame(s, Main.withSigning(s, Map.of(Main.ENV_SIGNING_KEY_FILE, file.toString()), true));
    }
}
