package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IsolatedBuildOutputTest {
    @TempDir Path dir;

    @Test void addsGroupReadWithoutGivingOtherUsersAccess() throws Exception {
        Path archive = Files.writeString(dir.resolve("archive"), "data");
        Files.setPosixFilePermissions(archive, PosixFilePermissions.fromString("rw-------"));
        Main.Build.makeGroupReadable(archive);
        var permissions = Files.getPosixFilePermissions(archive);
        assertTrue(permissions.contains(PosixFilePermission.GROUP_READ));
        assertFalse(permissions.contains(PosixFilePermission.OTHERS_READ));
    }

    @Test void refusesSymlinksAndDirectories() throws Exception {
        Path archive = Files.writeString(dir.resolve("archive"), "data");
        Path link = Files.createSymbolicLink(dir.resolve("link"), archive);
        assertThrows(IllegalStateException.class, () -> Main.Build.makeGroupReadable(link));
        assertThrows(IllegalStateException.class, () -> Main.Build.makeGroupReadable(dir));
    }
}
