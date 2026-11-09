package io.github.baokhang83.mnemo.warmup.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately small JSON reader/writer, enough for the manifest, so that the core library
 * needs no third-party dependency. Values are {@code Map<String,Object>} (insertion ordered),
 * {@code List<Object>}, {@code String}, {@code Long}, {@code Double}, {@code Boolean} and {@code null}.
 */
final class Json {

    private static final int MAX_DEPTH = 64;

    private Json() {}

    // ---- writing ----

    /** Pretty-printed with two-space indentation and a trailing newline. */
    static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0, true);
        return sb.append('\n').toString();
    }

    /** Compact form without any whitespace; deterministic for ordered maps, so it can be hashed. */
    static String writeCompact(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0, false);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object value, int depth, boolean pretty) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            quote(sb, s);
        } else if (value instanceof Boolean || value instanceof Long || value instanceof Integer) {
            sb.append(value);
        } else if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                throw new IllegalArgumentException("JSON cannot represent " + d);
            }
            sb.append(d);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, (Map<String, Object>) map, depth, pretty);
        } else if (value instanceof List<?> list) {
            writeArray(sb, (List<Object>) list, depth, pretty);
        } else {
            throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
        }
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> map, int depth, boolean pretty) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            newline(sb, depth + 1, pretty);
            quote(sb, e.getKey());
            sb.append(pretty ? ": " : ":");
            write(sb, e.getValue(), depth + 1, pretty);
        }
        newline(sb, depth, pretty);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<Object> list, int depth, boolean pretty) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            newline(sb, depth + 1, pretty);
            write(sb, item, depth + 1, pretty);
        }
        newline(sb, depth, pretty);
        sb.append(']');
    }

    private static void newline(StringBuilder sb, int depth, boolean pretty) {
        if (pretty) {
            sb.append('\n').append("  ".repeat(depth));
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---- reading ----

    /** @throws IllegalArgumentException on malformed input, naming the offset */
    static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.value(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("Unexpected content after the JSON value");
        }
        return value;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Invalid JSON at offset " + pos + ": " + message);
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("Nesting is too deep");
            }
            if (atEnd()) {
                throw error("Unexpected end of input");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("Unexpected character '" + c + "'");
                }
            };
        }

        Object literal(String word, Object result) {
            if (!s.startsWith(word, pos)) {
                throw error("Expected '" + word + "'");
            }
            pos += word.length();
            return result;
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw error("Expected a string key");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if (map.put(key, value(depth + 1)) != null) {
                    throw error("Duplicate key '" + key + "'");
                }
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else {
                    expect('}');
                    return map;
                }
            }
        }

        List<Object> array(int depth) {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(value(depth + 1));
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else {
                    expect(']');
                    return list;
                }
            }
        }

        String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw error("Unescaped control character in string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("Unterminated escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) {
                            throw error("Truncated \\u escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("Bad \\u escape");
                        }
                        pos += 4;
                    }
                    default -> throw error("Unknown escape '\\" + e + "'");
                }
            }
        }

        Object number() {
            int start = pos;
            if (peek('-')) {
                pos++;
            }
            digits();
            boolean integral = true;
            if (peek('.')) {
                integral = false;
                pos++;
                digits();
            }
            if (peek('e') || peek('E')) {
                integral = false;
                pos++;
                if (peek('+') || peek('-')) {
                    pos++;
                }
                digits();
            }
            String text = s.substring(start, pos);
            try {
                return integral ? (Object) Long.parseLong(text) : (Object) Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw error("Bad number '" + text + "'");
            }
        }

        void digits() {
            int start = pos;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
            }
            if (pos == start) {
                throw error("Expected a digit");
            }
        }

        boolean peek(char c) {
            return pos < s.length() && s.charAt(pos) == c;
        }

        void expect(char c) {
            if (!peek(c)) {
                throw error("Expected '" + c + "'");
            }
            pos++;
        }
    }
}
