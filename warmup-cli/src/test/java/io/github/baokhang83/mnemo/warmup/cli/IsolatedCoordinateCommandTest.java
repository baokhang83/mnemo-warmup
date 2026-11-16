package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IsolatedCoordinateCommandTest {
    @TempDir Path dir;

    @Test void requiresTheActualPublisherSecretNames() {
        assertEquals(Set.of("signer", "push"), Main.IsolatedCoordinate.protectedSecretNames("signer", "push", true));
        assertEquals(Set.of("signer"), Main.IsolatedCoordinate.protectedSecretNames("signer", null, false));
        assertThrows(IllegalArgumentException.class, () -> Main.IsolatedCoordinate.protectedSecretNames("", "push", true));
        assertThrows(IllegalArgumentException.class, () -> Main.IsolatedCoordinate.protectedSecretNames("signer", null, true));
        assertThrows(IllegalArgumentException.class, () -> Main.IsolatedCoordinate.protectedSecretNames("bad..name", "push", true));
    }

    @Test void checksDistinctAccountsAndTheClaimGroup() throws Exception {
        Main.IsolatedCoordinate.requireDistinctServiceAccounts("publisher", "trainer");
        assertThrows(IllegalArgumentException.class, () -> Main.IsolatedCoordinate.requireDistinctServiceAccounts("publisher", "publisher"));
        int gid = ((Number) Files.getAttribute(dir, "unix:gid")).intValue();
        if (gid > 0) {
            Main.IsolatedCoordinate.requireMatchingHandoffGroup(dir, java.util.Map.of("securityContext", java.util.Map.of("fsGroup", gid)));
            assertThrows(IllegalArgumentException.class, () -> Main.IsolatedCoordinate.requireMatchingHandoffGroup(dir, java.util.Map.of("securityContext", java.util.Map.of("fsGroup", gid + 1))));
        }
        assertThrows(IllegalArgumentException.class, () -> Main.IsolatedCoordinate.requireMatchingHandoffGroup(dir, java.util.Map.of()));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test void readsOneBoundedTrainingPodSpecPinnedByDigest() throws Exception {
        Path file = dir.resolve("pod.yaml");
        Files.writeString(file, "containers:\n  - name: trainer\n    image: example/app:1\n");
        String digest = sha256(Files.readAllBytes(file));
        assertEquals(1, ((java.util.List<?>) Main.IsolatedCoordinate.readTemplate(file, digest).get("containers")).size());
        Files.writeString(file, "containers:\n  - name: trainer\n    image: attacker/app:1\n");
        var ex = assertThrows(IllegalArgumentException.class, () -> Main.IsolatedCoordinate.readTemplate(file, digest));
        assertTrue(ex.getMessage().contains("does not match"), ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Main.IsolatedCoordinate.readTemplate(file, null));
        assertThrows(IllegalArgumentException.class, () -> Main.IsolatedCoordinate.readTemplate(file, digest.toUpperCase()));
        Files.writeString(file, "x: y\n---\nx: z\n");
        assertThrows(IllegalArgumentException.class, () -> Main.IsolatedCoordinate.readTemplate(file, sha256(Files.readAllBytes(file))));
        Files.write(file, new byte[(1 << 20) + 1]);
        assertThrows(IllegalArgumentException.class, () -> Main.IsolatedCoordinate.readTemplate(file, sha256(Files.readAllBytes(file))));
    }

    @Test void readsAllowedSecretsAndTheOwnerPod() {
        assertEquals(Set.of("db", "api"), Main.IsolatedCoordinate.allowedSecretNames(java.util.List.of("db", "api")));
        assertThrows(IllegalArgumentException.class, () -> Main.IsolatedCoordinate.allowedSecretNames(java.util.List.of("Bad_Name")));
        assertEquals(null, Main.IsolatedCoordinate.owner(java.util.Map.of()));
        assertEquals(new io.github.baokhang83.mnemo.warmup.core.IsolatedTrainingPodFactory.Owner("p", "u"),
                Main.IsolatedCoordinate.owner(java.util.Map.of("POD_NAME", "p", "POD_UID", "u")));
        assertThrows(IllegalArgumentException.class, () -> Main.IsolatedCoordinate.owner(java.util.Map.of("POD_NAME", "p")));
    }
}
