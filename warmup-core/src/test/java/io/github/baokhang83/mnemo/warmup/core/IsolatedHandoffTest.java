package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IsolatedHandoffTest {
    @TempDir Path tmp;

    @Test void createsUniqueGroupWritableDirectoriesAndRemovesOnlyItsOwn() throws Exception {
        Path other = Files.writeString(tmp.resolve("unrelated"), "keep");
        try (var first = IsolatedHandoff.create(tmp); var second = IsolatedHandoff.create(tmp)) {
            assertFalse(first.name().equals(second.name()));
            assertTrue(first.name().startsWith("mnemo-run-"));
            assertEquals("rwxrwx---", PosixFilePermissions.toString(Files.getPosixFilePermissions(first.directory())));
            assertEquals(Files.getAttribute(tmp, "unix:gid"), Files.getAttribute(first.directory(), "unix:gid"));
            Files.writeString(first.directory().resolve("archive"), "data");
            Path external = Files.createSymbolicLink(first.directory().resolve("external"), other);
            assertTrue(Files.isSymbolicLink(external));
        }
        assertEquals("keep", Files.readString(other));
        try (var entries = Files.list(tmp)) { assertEquals(1, entries.count()); }
    }

    @Test void refusesMissingOrSymlinkRoots() throws Exception {
        assertThrows(java.io.IOException.class, () -> IsolatedHandoff.create(tmp.resolve("missing")));
        Path link = Files.createSymbolicLink(tmp.resolve("root-link"), tmp);
        assertThrows(java.io.IOException.class, () -> IsolatedHandoff.create(link));
    }

    @Test void refusesToFollowAnExcessivelyDeepHandoff() throws Exception {
        var handoff = IsolatedHandoff.create(tmp);
        Path dir = handoff.directory();
        for (int i = 0; i < 35; i++) dir = Files.createDirectory(dir.resolve("d"));
        assertThrows(java.io.IOException.class, handoff::close);
        assertTrue(Files.exists(handoff.directory()));
    }
}
