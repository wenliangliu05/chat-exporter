package com.lion.chatexporter.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一个极小的、只读的 JSON 导航工具。
 *
 * <p>为什么不用 Jackson 的树模型？
 * Spring Boot 4 已经换成 Jackson 3（包名 tools.jackson.*），
 * 直接依赖它的 API 会让代码和版本绑死。这里让 RestClient 把响应体反序列化成
 * Map/List（走 Spring 自己的转换器），再用本类安全导航——既解耦又不会 NPE。
 */
public final class Json {

    private Json() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return (o instanceof List<?> l) ? (List<Object>) l : List.of();
    }

    /** 按路径逐层取 Map，任何一层缺失都返回空 map（不抛异常） */
    public static Map<String, Object> path(Object root, String... keys) {
        Object cur = root;
        for (String k : keys) {
            cur = map(cur).get(k);
            if (cur == null) {
                return Map.of();
            }
        }
        return map(cur);
    }

    public static String str(Object o, String def) {
        if (o == null) {
            return def;
        }
        String s = String.valueOf(o);
        return s.isBlank() ? def : s;
    }

    public static long num(Object o, long def) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static double dbl(Object o, double def) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return def;
    }

    /** 在子树里找第一个 http(s) 链接——用于把附件/图片块转成 Markdown 链接 */
    public static String firstUrl(Object node) {
        String[] hit = new String[1];
        findUrl(node, hit, 0);
        return hit[0];
    }

    private static void findUrl(Object node, String[] out, int depth) {
        if (node == null || depth > 6 || out[0] != null) {
            return;
        }
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String s && (s.startsWith("http://") || s.startsWith("https://")) && s.length() > 12) {
                    out[0] = s;
                    return;
                }
                findUrl(v, out, depth + 1);
                if (out[0] != null) {
                    return;
                }
            }
        } else if (node instanceof List<?> l) {
            for (Object o : l) {
                findUrl(o, out, depth + 1);
                if (out[0] != null) {
                    return;
                }
            }
        }
    }

    /** 在整棵子树里找第一个 key 为 content/text 的非空字符串——用于拍平平台各种 block */
    public static String firstText(Object node) {
        List<String> hits = new ArrayList<>();
        collectText(node, hits, 0);
        return hits.isEmpty() ? "" : hits.get(0);
    }

    private static void collectText(Object node, List<String> out, int depth) {
        if (node == null || depth > 6 || !out.isEmpty()) {
            return;
        }
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object v = e.getValue();
                if (v instanceof String s && !s.isBlank()
                        && ("content".equals(k) || "text".equals(k) || "text_content".equals(k))) {
                    out.add(s);
                    return;
                }
                collectText(v, out, depth + 1);
                if (!out.isEmpty()) {
                    return;
                }
            }
        } else if (node instanceof List<?> l) {
            for (Object o : l) {
                collectText(o, out, depth + 1);
                if (!out.isEmpty()) {
                    return;
                }
            }
        }
    }
}
