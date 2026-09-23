package com.example.gemfire.stats;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and pretty-printer. GemFire bundles Jackson, but 10.1 ships Jackson 2 and
 * 10.3 ships Jackson 3 (different package names), so the collector doesn't rely on either.
 */
final class Json {

    private Json() {
    }

    /** Parses JSON into Maps, Lists, Strings, Longs, Doubles, Booleans and nulls. */
    static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.value();
        parser.skipWhitespace();
        if (parser.pos != text.length()) {
            throw parser.error("Unexpected trailing content");
        }
        return value;
    }

    /** Writes Maps, Collections, arrays, Strings, Numbers, Booleans and nulls as indented JSON. */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, "");
        return out.append('\n').toString();
    }

    private static void write(Object value, StringBuilder out, String indent) {
        String childIndent = indent + "  ";
        if (value == null) {
            out.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.append(childIndent);
                writeString(String.valueOf(entry.getKey()), out);
                out.append(": ");
                write(entry.getValue(), out, childIndent);
                out.append(++i < map.size() ? ",\n" : "\n");
            }
            out.append(indent).append('}');
        } else if (value instanceof Collection<?> || value.getClass().isArray()) {
            List<Object> items = new ArrayList<>();
            if (value instanceof Collection<?> collection) {
                items.addAll(collection);
            } else {
                for (int i = 0; i < Array.getLength(value); i++) {
                    items.add(Array.get(value, i));
                }
            }
            if (items.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < items.size(); i++) {
                out.append(childIndent);
                write(items.get(i), out, childIndent);
                out.append(i + 1 < items.size() ? ",\n" : "\n");
            }
            out.append(indent).append(']');
        } else {
            writeString(value.toString(), out);
        }
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Object value() {
            skipWhitespace();
            if (pos >= text.length()) {
                throw error("Unexpected end of input");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                map.put(key, value());
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else {
                    expect('}');
                    return map;
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else {
                    expect(']');
                    return list;
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw error("Unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> out.append(escaped);
                }
            }
        }

        private Object number() {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String number = text.substring(start, pos);
            if (number.isEmpty()) {
                throw error("Unexpected character '" + text.charAt(pos) + "'");
            }
            return number.matches("-?\\d+") ? (Object) Long.valueOf(number) : Double.valueOf(number);
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) {
                throw error("Expected " + word);
            }
            pos += word.length();
            return value;
        }

        private boolean peek(char c) {
            return pos < text.length() && text.charAt(pos) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("Expected '" + c + "'");
            }
            pos++;
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + pos);
        }
    }
}
