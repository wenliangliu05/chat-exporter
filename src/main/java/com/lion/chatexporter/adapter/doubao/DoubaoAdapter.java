package com.lion.chatexporter.adapter.doubao;

import com.lion.chatexporter.adapter.AdapterContext;
import com.lion.chatexporter.adapter.ChatPlatformAdapter;
import com.lion.chatexporter.config.AppProperties;
import com.lion.chatexporter.domain.Conversation;
import com.lion.chatexporter.domain.Message;
import com.lion.chatexporter.util.Json;
import com.lion.chatexporter.util.JsonParser;
import com.lion.chatexporter.util.JsonWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 豆包适配器（真实接口，契约来自已跑通的 Python v3）。
 *
 * <p>三个必须记住的细节（都是踩过的坑）：
 * <ol>
 *   <li>公共参数（aid / device_platform / version_code …）放在 <b>URL 查询串</b>，不在 body 里；</li>
 *   <li>必须带 {@code agw-js-conv: str} 头，否则 401；</li>
 *   <li>会话列表的翻页开关在 {@code option} 对象里，{@code pc_pin_query_type=0} 才返回全部（=1 会漏）。</li>
 * </ol>
 */
@Component
public class DoubaoAdapter implements ChatPlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(DoubaoAdapter.class);

    /** JS 的 MAX_SAFE_INTEGER：豆包用它表示"从最新一条往历史翻" */
    private static final long ANCHOR_START = 9007199254740991L;

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36 Edg/150.0.0.0";

    private final AppProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public DoubaoAdapter(AppProperties props) {
        this.props = props;
    }

    @Override
    public String platformId() {
        return "doubao";
    }

    // ==================== 会话列表 ====================

    @Override
    public List<Conversation> listConversations(AdapterContext ctx) {
        Map<String, Conversation> all = new LinkedHashMap<>();

        Map<String, Object> body = downlink(post(ctx, "/im/chain/recent_conv", convPayload(0, 0)),
                "pull_recent_conv_chain_downlink_body");
        long anchor = collect(body, all);
        boolean hasMore = Json.bool(body.get("has_more"), false);
        int page = 1;

        while (hasMore && page < 40) {
            page++;
            pace(ctx);
            Map<String, Object> nb = downlink(post(ctx, "/im/chain/recent_conv", convPayload(1, anchor)),
                    "pull_recent_conv_chain_downlink_body");
            int before = all.size();
            long pageMin = collect(nb, all);
            hasMore = Json.bool(nb.get("has_more"), false);
            log.debug("会话列表第 {} 页: {} 条, 新增 {}, 累计 {}",
                    page, Json.list(nb.get("cells")).size(), all.size() - before, all.size());
            if (all.size() == before) {
                break;                       // 没有新会话就收手，防死循环
            }
            anchor = pageMin;
        }

        for (String extra : extraIds()) {
            all.computeIfAbsent(extra, id -> new Conversation(id, "(手工补录 " + id + ")"));
        }

        List<Conversation> list = new ArrayList<>(all.values());
        list.sort(Comparator.comparingLong(c -> {
            try {
                return Long.parseLong(c.convKey());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }));
        log.info("豆包会话列表完成，共 {} 个（{} 页）", list.size(), page);
        return list;
    }

    private long collect(Map<String, Object> body, Map<String, Conversation> sink) {
        long min = Long.MAX_VALUE;
        for (Object o : Json.list(body.get("cells"))) {
            Map<String, Object> cell = Json.map(o);
            Map<String, Object> conv = cell.containsKey("conversation") ? Json.map(cell.get("conversation")) : cell;
            String id = Json.str(conv.get("conversation_id"), "");
            long pv = Json.num(cell.get("position_version"), 0);
            if (pv > 0) {
                min = Math.min(min, pv);
            }
            if (!id.isBlank()) {
                Conversation c = new Conversation(id, Json.str(conv.get("name"), "(无标题)"));
                c.positionVersion(pv);
                sink.putIfAbsent(id, c);
            }
        }
        return min == Long.MAX_VALUE ? 0 : min;
    }

    private Map<String, Object> convPayload(int direction, long convVersion) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("not_need_message", true);
        option.put("need_complete_conversation", true);
        option.put("need_coco_conversation", true);
        option.put("need_coco_bot", true);
        option.put("need_pc_pin_chain", true);
        option.put("pc_pin_query_type", 0);   // 0=全部会话

        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("limit", props.doubao().pageLimit());
        inner.put("message_count_per_conv", 1);
        inner.put("api_version", 1);
        inner.put("conv_version", convVersion);
        inner.put("direction", direction);
        inner.put("option", option);

        return envelope(3200, "pull_recent_conv_chain_uplink_body", inner, "conv_");
    }

    // ==================== 消息 ====================

    @Override
    public List<Message> fetchMessages(AdapterContext ctx, String convKey, long sinceSeq) {
        List<Message> out = new ArrayList<>();
        long anchor = ANCHOR_START;
        int page = 0;

        while (page++ < props.maxPages()) {
            Map<String, Object> body = downlink(post(ctx, "/im/chain/single", msgPayload(convKey, anchor)),
                    "pull_singe_chain_downlink_body");
            List<Object> msgs = Json.list(body.get("messages"));
            if (msgs.isEmpty()) {
                break;
            }
            long pageMin = Long.MAX_VALUE;
            for (Object o : msgs) {
                for (Message m : toMessages(o)) {
                    if (m.seq() > sinceSeq) {
                        out.add(m);
                    }
                }
                long idx = Json.num(Json.map(o).get("index_in_conv"),
                        Json.num(Json.map(o).get("index"), -1));
                if (idx >= 0) {
                    pageMin = Math.min(pageMin, idx);
                }
            }
            boolean hasMore = Json.bool(body.get("has_more"), false);
            long next = Json.num(body.get("next_index"), pageMin == Long.MAX_VALUE ? 0 : pageMin - 1);
            if (!hasMore || next <= 0 || next >= anchor) {
                break;
            }
            anchor = next;
            pace(ctx);
        }

        out.sort(Comparator.comparingLong(Message::seq));
        return out;
    }

    private Map<String, Object> msgPayload(String convKey, long anchor) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("conversation_id", convKey);
        inner.put("anchor_index", anchor);
        inner.put("conversation_type", 3);
        inner.put("direction", 1);           // 往历史方向翻
        inner.put("limit", props.doubao().batchSize());
        inner.put("ext", Map.of());
        inner.put("filter", Map.of("index_list", List.of()));
        inner.put("evaluate_ab_params", "");
        inner.put("evaluate_common_params", "");

        Map<String, Object> p = envelope(3100, "pull_singe_chain_uplink_body", inner, "msg_");
        p.put("sequence_id", "msg_" + System.currentTimeMillis() + "_" + anchor);
        return p;
    }

    /**
     * 一条平台消息 → 若干条领域消息。
     *
     * <p>为什么要拆？现代豆包把「思考块 / 正文块 / 工具块」都塞在同一个
     * {@code content_block} <b>数组</b>里。拆开存，Markdown 才能把思考折叠成引用、正文直出。
     *
     * <p>字段对照（和旧版 v3 代码不同，接口已经换代）：
     * <ul>
     *   <li>序号：{@code index_in_conv}（字符串），不是 {@code index}</li>
     *   <li>角色：{@code user_type} 1=我，2=助手</li>
     *   <li>块：{@code content_block} 是 List，每块有 {@code block_type} 与 {@code content}</li>
     * </ul>
     */
    private List<Message> toMessages(Object raw) {
        Map<String, Object> m = Json.map(raw);
        long idx = Json.num(m.get("index_in_conv"), Json.num(m.get("index"), -1));
        if (idx < 0) {
            return List.of();
        }
        int userType = (int) Json.num(m.get("user_type"), 2);
        String role = userType == 1 ? "user" : "assistant";

        StringBuilder thinking = new StringBuilder();
        StringBuilder text = new StringBuilder();
        String tool = null;
        String attach = null;
        String search = null;

        for (Object o : Json.list(m.get("content_block"))) {
            Map<String, Object> b = Json.map(o);
            int bt = (int) Json.num(b.get("block_type"), 0);
            String t = Json.firstText(b.get("content"));
            if (t.isBlank()) {
                t = Json.firstText(b);
            }
            if (t.isBlank()) {
                continue;
            }
            switch (bt) {
                case 10040 -> thinking.append(t).append("\n");
                case 10000 -> text.append(t).append("\n\n");
                case 10019, 10024 -> tool = (tool == null) ? t : tool;
                case 10052 -> {
                    if (attach == null) {
                        String url = Json.firstUrl(b.get("content"));
                        if (url != null) {
                            boolean img = url.matches("(?i).*\\.(png|jpe?g|gif|webp|bmp)(\\?.*)?$");
                            attach = (img ? "![](" : "[附件](") + url + (img ? ")" : ")");
                        } else if (!t.isBlank()) {
                            attach = t;
                        }
                    }
                }
                case 10025 -> search = (search == null) ? t : search;
                default -> {
                    if (text.length() < 200) {
                        text.append(t).append("\n\n");
                    }
                }
            }
        }

        if (thinking.length() == 0) {
            String tc = Json.str(m.get("thinking_content"), "");
            if (!tc.isBlank()) {
                thinking.append(tc);
            }
        }
        if (text.length() == 0) {
            String plain = Json.str(m.get("content"), "");
            if (!plain.isBlank()) {
                text.append(plain);
            }
        }

        List<Message> out = new ArrayList<>();
        if (!thinking.isEmpty()) {
            out.add(Message.of(idx, role, "thinking", thinking.toString().trim()));
        }
        if (!text.isEmpty()) {
            out.add(Message.of(idx, role, "text", text.toString().trim()));
        }
        if (tool != null) {
            out.add(Message.of(idx, role, "tool", tool));
        }
        if (attach != null) {
            out.add(Message.of(idx, role, "attachment", attach));
        }
        if (search != null) {
            out.add(Message.of(idx, role, "search", search));
        }
        return out;
    }

    // ==================== HTTP ====================

    private Map<String, Object> envelope(int cmd, String key, Map<String, Object> inner, String seqPrefix) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("cmd", cmd);
        p.put("uplink_body", Map.of(key, inner));
        p.put("sequence_id", seqPrefix + System.currentTimeMillis());
        p.put("channel", 2);
        p.put("version", "1");
        return p;
    }

    /** 公共参数必须走查询串 —— 放 body 里会得到 status_code=712012002「不支持编码类型」 */
    private String url(String path) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("aid", "497858");
        q.put("real_aid", "497858");
        q.put("device_platform", "web");
        q.put("doubao_device_platform", "web");
        q.put("version_code", "20800");
        q.put("pc_version", "3.29.6");
        q.put("doubao_pc_version", "3.29.6");
        q.put("language", "zh");
        q.put("region", "CN");
        q.put("sys_region", "CN");
        q.put("pkg_type", "release_version");
        q.put("samantha_web", "1");
        q.put("web_platform", "browser");
        q.put("use-olympus-account", "1");

        StringBuilder sb = new StringBuilder(props.doubao().baseUrl()).append(path).append('?');
        q.forEach((k, v) -> sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8)).append('&'));
        return sb.toString();
    }

    /**
     * 发一个豆包请求。
     *
     * <p>为什么不用 RestClient？因为服务端要求 Content-Type 精确为
     * {@code application/json; encoding=utf-8}，而 Spring 的消息转换器会把
     * {@code encoding} 参数抹掉，结果返回 {@code 712012002 不支持编码类型}。
     * 直连 HttpClient 可以原样发出这个头。
     */
    private Map<String, Object> post(AdapterContext ctx, String path, Map<String, Object> payload) {
        if (ctx.cookie() == null || ctx.cookie().isBlank()) {
            throw new IllegalStateException("缺少豆包 Cookie：界面粘贴，或写到 data/cookie.txt");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/json; encoding=utf-8")
                .header("Cookie", ctx.cookie())
                .header("agw-js-conv", "str")
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", "https://www.doubao.com")
                .header("Referer", "https://www.doubao.com/chat/")
                .header("User-Agent", UA)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(JsonWriter.write(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("请求豆包失败：" + e.getMessage(), e);
        }
        if (resp.statusCode() == 401) {
            throw new IllegalStateException("豆包 401：Cookie 失效，请重新登录网页版后复制新 Cookie");
        }
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("豆包 HTTP " + resp.statusCode() + "：" + head(resp.body()));
        }

        Map<String, Object> res = Json.map(JsonParser.parse(resp.body()));
        long status = Json.num(res.get("status_code"), 0);
        if (status != 0) {
            throw new IllegalStateException("豆包错误 " + status + "：" + Json.str(res.get("status_desc"), ""));
        }
        return res;
    }

    private String head(String s) {
        return s == null ? "" : (s.length() > 160 ? s.substring(0, 160) : s);
    }

    private Map<String, Object> downlink(Map<String, Object> res, String key) {
        return Json.path(res, "downlink_body", key);
    }

    private void pace(AdapterContext ctx) {
        try {
            Thread.sleep(Math.max(0, ctx.requestIntervalMs()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> extraIds() {
        String raw = props.doubao().extraConversationIds();
        List<String> ids = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String s : raw.split(",")) {
            if (!s.isBlank()) {
                ids.add(s.trim());
            }
        }
        return ids;
    }
}
