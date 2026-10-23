package com.lion.chatexporter.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（递归下降），只产出 Map / List / String / Long / Double / Boolean / null。
 *
 * <p>够用即可：豆包返回的就是标准 JSON，不需要流式解析，也不需要自定义类型绑定。
 */
public final class JsonParser {

    private final String src;
    private int i;

    private JsonParser(String src) {
        this.src = src;
    }

    public static Object parse(String json) {
        JsonParser p = new JsonParser(json);
        p.ws();
        Object v = p.value();
        p.ws();
        return v;
    }

    private Object value() {
        char c = peek();
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> { expect("true"); yield Boolean.TRUE; }
            case 'f' -> { expect("false"); yield Boolean.FALSE; }
            case 'n' -> { expect("null"); yield null; }
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;                       // {
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String k = string();
            ws();
            i++;                   // :
            ws();
            m.put(k, value());
            ws();
            char c = src.charAt(i++);
            if (c == '}') {
                return m;
            }
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;                       // [
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = src.charAt(i++);
            if (c == ']') {
                return l;
            }
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++;                       // "
        while (true) {
            char c = src.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = src.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(src.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < src.length() && "-+.eE0123456789".indexOf(src.charAt(i)) >= 0) {
            i++;
        }
        String s = src.substring(start, i);
        if (s.contains(".") || s.contains("e") || s.contains("E")) {
            return Double.parseDouble(s);
        }
        return Long.parseLong(s);
    }

    private void expect(String word) {
        if (!src.startsWith(word, i)) {
            throw new IllegalStateException("JSON 解析失败，位置 " + i);
        }
        i += word.length();
    }

    private char peek() {
        if (i >= src.length()) {
            throw new IllegalStateException("JSON 意外结束");
        }
        return src.charAt(i);
    }

    private void ws() {
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
            i++;
        }
    }
}
