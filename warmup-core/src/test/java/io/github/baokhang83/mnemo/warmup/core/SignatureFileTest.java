package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignatureFileTest {

    private static final Path VECTOR = Path.of("..", "launcher", "testdata", "signing", "vector.json");

    // the file text is part of the contract: Go writes and reads the same bytes
    @SuppressWarnings("unchecked")
    @Test
    void matchesTheFrozenVectorAndRoundTrips() throws Exception {
        assumeTrue(Files.isRegularFile(VECTOR), "the launcher directory is not part of this checkout");
        Map<String, Object> v = (Map<String, Object>) Json.parse(Files.readString(VECTOR, StandardCharsets.UTF_8));
        var signature = new ArchiveSignature((String) v.get("keyId"), HexFormat.of().parseHex((String) v.get("signature")));
        String repository = (String) ((Map<String, Object>) v.get("statement")).get("repository");
        String text = SignatureFile.text(signature, repository);
        assertEquals(v.get("signatureFile"), text);
        var parsed = SignatureFile.parse(text);
        assertEquals(repository, parsed.repository());
        assertEquals(signature.keyId(), parsed.signature().keyId());
        assertArrayEquals(signature.signature(), parsed.signature().signature());
    }

    @Test
    void refusesAnythingThatIsNotExactlyTheFormat() {
        String good = SignatureFile.text(new ArchiveSignature("0".repeat(16), new byte[64]), "team/app");
        for (String bad : new String[] {
                "", "hello\n", good.replace("signature-v1", "signature-v2"), good.substring(0, good.length() - 1), // no final newline
                good + "extra: x\n", good.replace("keyid: ", "id: "), good.replace("repository: team/app", "repository: Team/App"),
                good.replaceFirst("signature: .*", "signature: !!!"), good.replace("repository: team/app", "repository: team/app\nsize: 1")}) {
            assertThrows(IllegalArgumentException.class, () -> SignatureFile.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> SignatureFile.text(new ArchiveSignature("0".repeat(16), new byte[64]), "bad\nrepo"));
    }
}
