package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void roundTripsNestedValues() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "a \"quoted\" \\ back\nslash\ttab \u0001 é €");
        value.put("n", 42L);
        value.put("neg", -7L);
        value.put("ratio", 1.5);
        value.put("yes", true);
        value.put("nothing", null);
        value.put("list", List.of("x", 1L, List.of(), Map.of()));
        value.put("obj", Map.of("k", "v"));

        for (String text : List.of(Json.writePretty(value), Json.writeCompact(value))) {
            assertEquals(value, Json.parse(text));
        }
    }

    @Test
    void compactFormHasNoWhitespaceAndKeepsKeyOrder() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("b", 1L);
        value.put("a", List.of("x"));
        assertEquals("{\"b\":1,\"a\":[\"x\"]}", Json.writeCompact(value));
    }

    @Test
    void parsesEscapesAndNumbers() {
        assertEquals("A/\u00e9\n", Json.parse("\"\\u0041\\/\\u00e9\\n\""));
        assertEquals(12L, Json.parse(" 12 "));
        assertEquals(-3.5e2, Json.parse("-3.5e2"));
        assertEquals(List.of(), Json.parse("[ ]"));
        assertNull(Json.parse("null"));
    }

    @Test
    void rejectsMalformedInputWithAnOffset() {
        for (String bad : List.of(
                "", "{", "[1,", "{\"a\":1,}", "{\"a\" 1}", "{a:1}", "[1 2]", "\"abc", "\"\\x\"", "\"\\u12\"", "tru",
                "01x", "-", "1.", "{} extra", "\"tab\there\"", "{\"a\":1,\"a\":2}")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
            assertTrue(e.getMessage().startsWith("Invalid JSON at offset"), bad + " -> " + e.getMessage());
        }
    }

    @Test
    void limitsNesting() {
        String deep = "[".repeat(200) + "]".repeat(200);
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deep));
    }

    @Test
    void refusesValuesItCannotWrite() {
        assertThrows(IllegalArgumentException.class, () -> Json.writeCompact(new Object()));
        assertThrows(IllegalArgumentException.class, () -> Json.writeCompact(Double.NaN));
    }
}
