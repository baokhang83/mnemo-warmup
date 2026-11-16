package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warmup.core.ArchiveVerifier;
import io.github.baokhang83.mnemo.warmup.core.SigningKey;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeygenCommandTest {

    @TempDir
    Path tmp;

    private record Result(int code, String out, String err) {}

    private Result run(String... args) {
        var cmd = Main.commandLine();
        var out = new StringWriter();
        var err = new StringWriter();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        int code = cmd.execute(args);
        return new Result(code, out.toString(), err.toString());
    }

    @Test
    void writesAKeyFileAndPrintsOnlyThePublicHalf() throws Exception {
        Path file = tmp.resolve("k.key");
        Result r = run("keygen", "--out", file.toString());
        assertEquals(0, r.code(), r.err());
        SigningKey key = SigningKey.fromFile(file);
        assertTrue(r.out().contains("Key id:      " + key.keyId()), r.out());
        assertTrue(r.out().contains("MNEMO_WARMUP_SIGNING_PUBLIC_KEYS=" + key.publicKeyEntry()), r.out());
        String seed = file.toFile().length() > 0
                ? Files.readAllLines(file).get(3).substring("seed: ".length()) : "";
        assertFalse(r.out().contains(seed) || r.err().contains(seed), "the private seed must never be printed");
        // what it prints is exactly what a pod's verifier accepts
        var notes = new ArrayList<String>();
        assertFalse(ArchiveVerifier.fromEntries(key.publicKeyEntry(), notes::add).isEmpty());
        assertTrue(notes.isEmpty());
        assertTrue(r.out().contains("kubectl -n <namespace> create secret generic mnemo-warmup-signing --from-file=signing.key=" + file));
    }

    @Test
    void thePrivateFileIsReadableByTheOwnerOnly() throws Exception {
        Path file = tmp.resolve("k.key");
        assertEquals(0, run("keygen", "--out", file.toString()).code());
        try {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        } catch (UnsupportedOperationException notPosix) {
            // a file system without POSIX permissions has nothing to check
        }
    }

    @Test
    void neverOverwritesAnExistingFile() throws Exception {
        Path file = tmp.resolve("k.key");
        Files.writeString(file, "precious");
        Result r = run("keygen", "--out", file.toString());
        assertEquals(2, r.code());
        assertTrue(r.err().contains("never overwritten"), r.err());
        assertEquals("precious", Files.readString(file));
    }

    @Test
    void eachRunMakesADifferentKey() {
        run("keygen", "--out", tmp.resolve("a").toString());
        run("keygen", "--out", tmp.resolve("b").toString());
        assertFalse(SigningKey.fromFile(tmp.resolve("a")).keyId().equals(SigningKey.fromFile(tmp.resolve("b")).keyId()));
    }

    @Test
    void reportsAnUnwritableLocationAndABadSecretName() {
        Result missingDir = run("keygen", "--out", tmp.resolve("no/such/dir/k").toString());
        assertEquals(2, missingDir.code());
        assertTrue(missingDir.err().contains("cannot write"), missingDir.err());
        Result badName = run("keygen", "--out", tmp.resolve("x").toString(), "--secret-name", "Bad_Name");
        assertEquals(2, badName.code());
        assertFalse(Files.exists(tmp.resolve("x")), "nothing is written when the arguments are wrong");
    }

    @Test
    void theBase64OfThePublicKeyIsPlainStandardBase64() {
        run("keygen", "--out", tmp.resolve("k").toString());
        String entry = SigningKey.fromFile(tmp.resolve("k")).publicKeyEntry();
        assertEquals(32, Base64.getDecoder().decode(entry.substring(entry.indexOf('=') + 1)).length);
    }
}
