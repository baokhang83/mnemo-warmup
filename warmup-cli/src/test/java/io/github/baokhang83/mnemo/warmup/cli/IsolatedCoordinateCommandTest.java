package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test void readsOneBoundedTrainingPodSpec() throws Exception {
        Path file = dir.resolve("pod.yaml");
        Files.writeString(file, "containers:\n  - name: trainer\n    image: example/app:1\n");
        assertEquals(1, ((java.util.List<?>) Main.IsolatedCoordinate.readTemplate(file).get("containers")).size());
        Files.writeString(file, "x: y\n---\nx: z\n");
        assertThrows(IllegalArgumentException.class, () -> Main.IsolatedCoordinate.readTemplate(file));
        Files.write(file, new byte[(1 << 20) + 1]);
        assertThrows(IllegalArgumentException.class, () -> Main.IsolatedCoordinate.readTemplate(file));
    }
}
