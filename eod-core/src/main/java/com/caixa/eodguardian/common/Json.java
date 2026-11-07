package com.caixa.eodguardian.common;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.*;

/**
 * Minimal, dependency-free JSON writer/parser. Handles Map, Collection, arrays, records,
 * enums, numbers, booleans, strings, java.time types (ISO strings) and objects exposing toMap().
 */
public final class Json {
    private Json() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { quote(sb, s); return; }
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short) { sb.append(v); return; }
        if (v instanceof BigDecimal bd) { sb.append(bd.toPlainString()); return; }
        if (v instanceof Double d) { sb.append(d.isNaN() || d.isInfinite() ? "null" : trimDouble(d)); return; }
        if (v instanceof Float f) { sb.append(trimDouble(f.doubleValue())); return; }
        if (v instanceof Number n) { sb.append(n); return; }
        if (v instanceof Enum<?> e) { quote(sb, e.name()); return; }
        if (v instanceof TemporalAccessor || v instanceof java.util.UUID) { quote(sb, v.toString()); return; }
        if (v instanceof JsonSerializable js) { write(sb, js.toMap()); return; }
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
            return;
        }
        if (v.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(v);
            sb.append('[');
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(',');
                write(sb, java.lang.reflect.Array.get(v, i));
            }
            sb.append(']');
            return;
        }
        if (v.getClass().isRecord()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (RecordComponent rc : v.getClass().getRecordComponents()) {
                try {
                    rc.getAccessor().setAccessible(true);
                    m.put(rc.getName(), rc.getAccessor().invoke(v));
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
            write(sb, m);
            return;
        }
        quote(sb, v.toString());
    }

    private static String trimDouble(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
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
                case '<' -> sb.append("\\u003c"); // safe to embed in HTML
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ parser

    public static Object parse(String text) {
        if (text == null || text.isBlank()) return null;
        Parser p = new Parser(text);
        Object v = p.value();
        p.ws();
        if (p.pos != text.length()) throw new IllegalArgumentException("Trailing characters in JSON at " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        if (o == null) return new LinkedHashMap<>();
        if (!(o instanceof Map)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) o;
    }

    private static final class Parser {
        final String s;
        int pos;
        int depth;

        Parser(String s) { this.s = s; }

        void ws() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }

        Object value() {
            ws();
            if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            if (++depth > 64) throw new IllegalArgumentException("JSON nesting too deep");
            try {
                char c = s.charAt(pos);
                return switch (c) {
                    case '{' -> object();
                    case '[' -> array();
                    case '"' -> string();
                    case 't' -> literal("true", Boolean.TRUE);
                    case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null);
                    default -> number();
                };
            } finally {
                depth--;
            }
        }

        Object literal(String word, Object val) {
            if (!s.startsWith(word, pos)) throw new IllegalArgumentException("Bad literal at " + pos);
            pos += word.length();
            return val;
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            ws();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                m.put(k, value());
                ws();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("Expected , or } at " + pos);
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++;
            ws();
            if (peek() == ']') { pos++; return l; }
            while (true) {
                l.add(value());
                ws();
                char c = next();
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("Expected , or ] at " + pos);
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw new IllegalArgumentException("Unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16)); pos += 4; }
                        default -> sb.append(e);
                    }
                } else sb.append(c);
            }
        }

        Object number() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            String n = s.substring(start, pos);
            if (n.isEmpty()) throw new IllegalArgumentException("Unexpected character at " + start);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return new BigDecimal(n);
            try { return Long.parseLong(n); } catch (NumberFormatException e) { return new BigDecimal(n); }
        }

        char peek() { return pos < s.length() ? s.charAt(pos) : 0; }
        char next() { if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end"); return s.charAt(pos++); }
        void expect(char c) { if (next() != c) throw new IllegalArgumentException("Expected '" + c + "' at " + (pos - 1)); }
    }

    // ------------------------------------------------------------------ helpers

    public static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof List ? (List<Object>) v : List.of();
    }
}
