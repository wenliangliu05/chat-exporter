package com.lion.chatexporter.adapter.openai;

import com.lion.chatexporter.adapter.AdapterContext;
import com.lion.chatexporter.adapter.ChatPlatformAdapter;
import com.lion.chatexporter.config.AppProperties;
import com.lion.chatexporter.domain.Conversation;
import com.lion.chatexporter.domain.Message;
import com.lion.chatexporter.util.Json;
import com.lion.chatexporter.util.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * ChatGPT（chatgpt.com）适配器。
 *
 * <p>和豆包最大的不同：ChatGPT 不是"一把 Cookie 打天下"，而是要两步：
 * <ol>
 *   <li>拿 Cookie 换 <b>accessToken</b>：{@code GET /api/auth/session}</li>
 *   <li>再用 {@code Authorization: Bearer <token>} 调 {@code /backend-api/*}</li>
 * </ol>
 *
 * <p>另一个关键差异：一个会话是<b>树</b>（编辑消息会产生分支），不是线性列表。
 * 所以要从 {@code current_node} 沿 {@code parent} 一路回溯到根，才能得到"界面上看到的那条主线"。
 *
 * <p>接口契约：
 * <pre>
 * GET /api/auth/session                       → { accessToken, user, account }
 * GET /backend-api/conversations?offset&limit → { items[], total }
 * GET /backend-api/conversation/{id}          → { mapping{}, current_node }
 * </pre>
 */
@Component
public class OpenAiAdapter implements ChatPlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAdapter.class);

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36 Edg/150.0.0.0";

    private final AppProperties props;
    /**
     * 强制 HTTP/1.1：Cloudflare 会按 TLS/HTTP2 指纹判断"是不是浏览器"，
     * Java HttpClient 默认走 HTTP/2，指纹很容易被判定为机器人 → 403。
     * Python/curl 之所以能过，正是因为它们是 HTTP/1.1 + 常规指纹。
     */
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** accessToken 缓存（约 10 分钟刷新一次，避免每次请求都换 token） */
    private volatile String cachedToken;
    private volatile long tokenExpiresAt;

    public OpenAiAdapter(AppProperties props) {
        this.props = props;
    }

    @Override
    public String platformId() {
        return "openai";
    }

    // ==================== 会话列表 ====================

    @Override
    public List<Conversation> listConversations(AdapterContext ctx) {
        String token = token(ctx);
        Map<String, Conversation> all = new LinkedHashMap<>();
        int offset = 0;
        int limit = 100;

        while (true) {
            String url = base() + "/backend-api/conversations?offset=" + offset
                    + "&limit=" + limit + "&order=updated";
            Map<String, Object> d = getJson(url, token, ctx);
            List<Object> items = Json.list(d.get("items"));
            if (items.isEmpty()) {
                break;
            }
            for (Object o : items) {
                Map<String, Object> it = Json.map(o);
                String id = Json.str(it.get("id"), "");
                if (id.isBlank()) {
                    continue;
                }
                Conversation c = new Conversation(id, Json.str(it.get("title"), "(无标题)"));
                c.positionVersion(Json.num(it.get("update_time"), 0));
                all.putIfAbsent(id, c);
            }
            int total = (int) Json.num(d.get("total"), all.size());
            offset += items.size();
            if (offset >= total || items.size() < limit) {
                break;
            }
            pace(ctx);
        }

        List<Conversation> list = new ArrayList<>(all.values());
        log.info("ChatGPT 会话列表完成，共 {} 个", list.size());
        return list;
    }

    // ==================== 单个会话的消息 ====================

    @Override
    public List<Message> fetchMessages(AdapterContext ctx, String convKey, long sinceSeq) {
        String token = token(ctx);
        Map<String, Object> d = getJson(base() + "/backend-api/conversation/" + convKey, token, ctx);
        Map<String, Object> mapping = Json.map(d.get("mapping"));
        String currentNode = Json.str(d.get("current_node"), "");

        // 沿 parent 回溯，得到当前分支的有序节点（从根到叶）
        LinkedList<Map<String, Object>> chain = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        String nodeId = currentNode;
        while (nodeId != null && !nodeId.isBlank() && visited.add(nodeId)) {
            Map<String, Object> node = Json.map(mapping.get(nodeId));
            if (node.isEmpty()) {
                break;
            }
            chain.addFirst(node);
            nodeId = Json.str(node.get("parent"), "");
        }

        List<Message> out = new ArrayList<>();
        long fallbackSeq = 0;
        for (Map<String, Object> node : chain) {
            Map<String, Object> m = Json.map(node.get("message"));
            if (m.isEmpty()) {
                continue;
            }
            Map<String, Object> meta = Json.map(m.get("metadata"));
            // 界面不显示的注入消息（系统提示、隐藏上下文）跳过
            if (Json.bool(meta.get("is_visually_hidden_from_conversation"), false)) {
                continue;
            }
            String role = Json.str(Json.map(m.get("author")).get("role"), "assistant");
            if ("system".equals(role) || "tool".equals(role) && Json.str(m.get("recipient"), "").isBlank()) {
                // 系统消息不进正文；工具消息仍保留（下面按 content_type 归类）
                if ("system".equals(role)) {
                    continue;
                }
            }

            double ct = Json.dbl(m.get("create_time"), 0);
            long seq = ct > 0 ? (long) (ct * 1000) : ++fallbackSeq;

            Parsed p = parseContent(m);
            if (p.text.isBlank()) {
                continue;
            }
            out.add(Message.of(seq, role, p.kind, p.text));
        }

        out.sort(Comparator.comparingLong(Message::seq));
        if (sinceSeq >= 0) {
            out.removeIf(m -> m.seq() <= sinceSeq);
        }
        return out;
    }

    /** 解析 content（text / multimodal_text / thoughts / code / execution_output / tool_*） */
    private Parsed parseContent(Map<String, Object> message) {
        Map<String, Object> content = Json.map(message.get("content"));
        String ctype = Json.str(content.get("content_type"), "text");
        List<Object> parts = Json.list(content.get("parts"));

        StringBuilder sb = new StringBuilder();
        String kind = "text";

        switch (ctype) {
            case "thoughts" -> {
                kind = "thinking";
                for (Object part : parts) {
                    String t = Json.firstText(part);
                    if (!t.isBlank()) {
                        sb.append(t).append("\n");
                    }
                }
            }
            case "multimodal_text" -> {
                for (Object part : parts) {
                    if (part instanceof String s) {
                        sb.append(s);
                        continue;
                    }
                    Map<String, Object> pm = Json.map(part);
                    String t = Json.str(pm.get("text"), "");
                    if (!t.isBlank()) {
                        sb.append(t);
                        continue;
                    }
                    // 图片：有 http 链接就渲染成 Markdown 图片，asset_pointer 只能占位
                    String url = Json.firstUrl(pm);
                    if (url != null) {
                        sb.append("\n\n![](").append(url).append(")\n\n");
                    } else {
                        sb.append("[图片]");
                    }
                }
            }
            case "code" -> {
                for (Object part : parts) {
                    if (part instanceof String s) {
                        sb.append(s);
                    } else {
                        Map<String, Object> pm = Json.map(part);
                        sb.append(Json.str(pm.get("text"), ""));
                    }
                }
            }
            case "execution_output" -> {
                kind = "tool";
                for (Object part : parts) {
                    sb.append(part instanceof String s ? s : Json.firstText(part));
                }
            }
            case "tool_call", "tool_result", "function" -> {
                kind = "tool";
                sb.append(JsonWriterSafe.of(content));
            }
            default -> {
                for (Object part : parts) {
                    if (part instanceof String s) {
                        sb.append(s);
                    } else {
                        String t = Json.firstText(part);
                        if (!t.isBlank()) {
                            sb.append(t);
                        }
                    }
                }
            }
        }
        return new Parsed(kind, sb.toString().trim());
    }

    private record Parsed(String kind, String text) {}

    /** 工具调用参数是结构化对象，简单序列化一下便于人工阅读 */
    private static final class JsonWriterSafe {
        static String of(Object o) {
            return com.lion.chatexporter.util.JsonWriter.write(o);
        }
    }

    // ==================== HTTP ====================

    /**
     * 体检用：只打一次 /api/auth/session，返回 plan 与过期时间（不返回 token 本身）。
     */
    public Map<String, Object> probeSession(AdapterContext ctx) {
        Map<String, Object> d = getJson(base() + "/api/auth/session", null, ctx);
        String token = Json.str(d.get("accessToken"), "");
        if (token.isBlank()) {
            throw new IllegalStateException("Cookie 已失效：未返回 accessToken");
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("plan", Json.str(Json.map(d.get("account")).get("planType"), "unknown"));
        out.put("expires", Json.str(d.get("expires"), null));
        return out;
    }

    /** Cookie → accessToken */
    private String token(AdapterContext ctx) {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpiresAt) {
            return cachedToken;
        }
        Map<String, Object> d = getJson(base() + "/api/auth/session", null, ctx);
        String t = Json.str(d.get("accessToken"), "");
        if (t.isBlank()) {
            throw new IllegalStateException("ChatGPT Cookie 无效：/api/auth/session 没返回 accessToken（请重新登录后复制整条 Cookie）");
        }
        cachedToken = t;
        tokenExpiresAt = now + 10 * 60 * 1000L;
        return t;
    }

    private Map<String, Object> getJson(String url, String bearer, AdapterContext ctx) {
        if (ctx.cookie() == null || ctx.cookie().isBlank()) {
            throw new IllegalStateException("缺少 ChatGPT Cookie：界面粘贴，或写到 data/cookie-openai.txt");
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Cookie", ctx.cookie())
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Encoding", "identity")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-US;q=0.7")
                .header("Referer", "https://chatgpt.com/")
                .header("Origin", "https://chatgpt.com")
                .header("sec-ch-ua", "\"Chromium\";v=\"150\", \"Not:A-Brand\";v=\"24\", \"Microsoft Edge\";v=\"150\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Windows\"")
                .header("sec-fetch-dest", "empty")
                .header("sec-fetch-mode", "cors")
                .header("sec-fetch-site", "same-origin")
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }

        HttpResponse<String> resp;
        try {
            resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("请求 ChatGPT 失败：" + e.getMessage(), e);
        }
        int code = resp.statusCode();
        if (code == 401) {
            cachedToken = null;
            throw new IllegalStateException("ChatGPT 401：Cookie / accessToken 已失效，请重新登录复制");
        }
        if (code == 403) {
            throw new IllegalStateException("ChatGPT 403：被 Cloudflare 拦了。请确认 Cookie 里含 cf_clearance，且是在同一浏览器/网络下复制的");
        }
        if (code >= 400) {
            throw new IllegalStateException("ChatGPT HTTP " + code + "：" + head(resp.body()));
        }
        return Json.map(JsonParser.parse(resp.body()));
    }

    private String head(String s) {
        return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) : s);
    }

    private String base() {
        String b = props.openai().baseUrl();
        return (b == null || b.isBlank()) ? "https://chatgpt.com" : b;
    }

    private void pace(AdapterContext ctx) {
        try {
            Thread.sleep(Math.max(0, ctx.requestIntervalMs()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
