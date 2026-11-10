package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ManifestStreamTest {
    @Test void roundTripsMultipleResourcesAndNestedFields() {
        String input = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: a\ndata:\n  value: 'on'\n---\napiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: b\n";
        var original = ManifestStream.parse(input);
        assertEquals(2, original.size());
        assertEquals(original, ManifestStream.parse(ManifestStream.render(original)));
    }
    @Test void rejectsDuplicateKeysAndObjectTags() {
        assertThrows(IllegalArgumentException.class, () -> ManifestStream.parse("kind: A\nkind: B\n"));
        assertThrows(IllegalArgumentException.class, () -> ManifestStream.parse("!!java.net.URL [http://example.com]\n"));
    }
    @Test void rejectsNonObjectDocuments() {
        assertThrows(IllegalArgumentException.class, () -> ManifestStream.parse("- item\n"));
    }
}
