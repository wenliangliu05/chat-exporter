package com.lion.chatexporter.service;

import com.lion.chatexporter.adapter.AdapterContext;
import com.lion.chatexporter.adapter.ChatPlatformAdapter;
import com.lion.chatexporter.adapter.openai.OpenAiAdapter;
import com.lion.chatexporter.config.AppProperties;
import com.lion.chatexporter.config.ProfileContext;
import com.lion.chatexporter.config.UserSettings;
import com.lion.chatexporter.repository.SnapshotRepository;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Profile 管理：数据目录、Cookie 状态、快照概览。
 *
 * <p>这里解决一个很实际的坑：`./data` 是相对路径，
 * 换个工作目录启动就会"看不见之前导出的数据"。
 * 所以界面必须显示<b>绝对路径</b>，并允许切换。
 */
@Service
public class ProfileService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ProfileContext ctx;
    private final AppProperties props;
    private final SnapshotRepository repo;
    private final List<ChatPlatformAdapter> adapters;
    private final UserSettings settings;

    public ProfileService(ProfileContext ctx, AppProperties props,
                          SnapshotRepository repo, List<ChatPlatformAdapter> adapters,
                          UserSettings settings) {
        this.ctx = ctx;
        this.props = props;
        this.repo = repo;
        this.adapters = adapters;
        this.settings = settings;
    }

    /** 本地记忆（界面还原用），以及它的存放位置（供界面展示，便于确认"没上网"） */
    public Map<String, Object> settingsView() {
        return settings.view();
    }

    /** 界面状态更新：{platform?, snapshotId?, conversationId?} */
    public Map<String, Object> updateSettings(Map<String, Object> patch) {
        String platform = patch.get("platform") == null ? null : String.valueOf(patch.get("platform"));
        Long snapshotId = asLong(patch.get("snapshotId"));
        Long conversationId = asLong(patch.get("conversationId"));
        settings.rememberUi(platform, snapshotId, conversationId);
        return settings.view();
    }

    private Long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 当前 profile 的完整状态（给前端首屏用） */
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataDir", ctx.dir());
        m.put("dbPath", ctx.dbPath());
        Path db = Path.of(ctx.dbPath());
        m.put("dbExists", Files.exists(db));
        m.put("dbSizeBytes", Files.exists(db) ? db.toFile().length() : 0);
        m.put("markdownDir", ctx.markdownDir().toString());

        List<Map<String, Object>> snaps = repo.findSnapshots();
        m.put("snapshotCount", snaps.size());
        long msgs = snaps.stream().mapToLong(s -> ((Number) s.getOrDefault("message_count", 0)).longValue()).sum();
        m.put("messageCount", msgs);
        m.put("snapshots", snaps);
        m.put("cookies", cookieStatuses());
        m.put("settings", settings.view());
        return m;
    }

    /** 各平台 Cookie：是否存在、多大、什么时候过期 */
    public List<Map<String, Object>> cookieStatuses() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String platform : new String[]{"doubao", "openai"}) {
            Path f = ctx.cookieFile(platform);
            // 兼容老布局：data/cookie.txt 是所有平台共用的兜底文件
            if (!Files.exists(f) && Files.exists(Path.of(ctx.dir(), "cookie.txt"))) {
                f = Path.of(ctx.dir(), "cookie.txt");
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("platform", platform);
            boolean exists = Files.exists(f);
            c.put("file", f.toString());
            c.put("exists", exists);
            c.put("sizeBytes", exists ? f.toFile().length() : 0);
            if (exists) {
                try {
                    String cookie = Files.readString(f).trim();
                    String expiry = platform.equals("doubao") ? doubaoExpiry(cookie) : null;
                    c.put("expiresAt", expiry);
                    c.put("status", statusOf(expiry));
                } catch (Exception e) {
                    c.put("status", "unreadable");
                }
            } else {
                c.put("status", "missing");
            }
            list.add(c);
        }
        return list;
    }

    /** 兼容配置项里的 cookie（只报"有/无"，不返回值） */
    public boolean configuredInYaml(String platform) {
        return "doubao".equals(platform)
                ? props.doubao().cookie() != null && !props.doubao().cookie().isBlank()
                : props.openai().cookie() != null && !props.openai().cookie().isBlank();
    }

    /** 豆包 sid_guard 里带过期时间：<sid>|<ts>|<ttl>|<yyyy-MM-dd HH:mm:ss> */
    private String doubaoExpiry(String cookie) {
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("sid_guard=")) {
                // Cookie 里的 | 常被 URL 编码成 %7C，先还原再切分
                String v = p.substring("sid_guard=".length()).replace("%7C", "|").replace("%7c", "|");
                String[] seg = v.split("\\|");
                if (seg.length >= 4 && seg[3].length() >= 10) {
                    // 形如 Tue%2C+17-Nov-2026+12%3A00%3A00+GMT —— 需要 URL 解码再解析
                    String raw = java.net.URLDecoder.decode(seg[3], java.nio.charset.StandardCharsets.UTF_8);
                    try {
                        var fmt = java.time.format.DateTimeFormatter
                                .ofPattern("EEE, dd-MMM-yyyy HH:mm:ss zzz", java.util.Locale.ENGLISH);
                        var t = java.time.ZonedDateTime.parse(raw, fmt);
                        return FMT.format(t.withZoneSameInstant(java.time.ZoneId.systemDefault()));
                    } catch (Exception ignored) {
                        return raw.length() >= 16 ? raw.substring(0, 16) : raw;
                    }
                }
                if (seg.length >= 2) {
                    try {
                        long ts = Long.parseLong(seg[1]);
                        if (ts > 1_000_000_000L) {
                            return FMT.format(LocalDateTime.ofInstant(Instant.ofEpochSecond(ts), ZoneId.systemDefault()));
                        }
                    } catch (NumberFormatException ignored) {
                        // 不是数字就算了
                    }
                }
            }
        }
        return null;
    }

    private String statusOf(String expiry) {
        if (expiry == null) {
            return "unknown";
        }
        try {
            LocalDateTime t = LocalDateTime.parse(expiry.replace(' ', 'T'));
            long days = java.time.Duration.between(LocalDateTime.now(), t).toDays();
            if (days < 0) {
                return "expired";
            }
            return days <= 3 ? "expiring" : "ok";
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ---------------- 操作 ----------------

    public Map<String, Object> activate(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("profile 路径不能为空");
        }
        ctx.activate(path.trim());
        return info();
    }

    public List<String> candidates() {
        List<String> list = new ArrayList<>();
        for (String p : new String[]{ctx.dir(), props.dataDir(),
                "./data", System.getProperty("user.home") + "/.chatexporter"}) {
            Path abs = Path.of(p).toAbsolutePath().normalize();
            if (!list.contains(abs.toString())) {
                list.add(abs.toString());
            }
        }
        return list;
    }

    /** 保存 Cookie 到 profile 目录（只在本地，data/ 已 gitignore） */
    public Map<String, Object> saveCookie(String platform, String cookie) {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("platform 不能为空");
        }
        if (cookie == null || cookie.isBlank()) {
            throw new IllegalArgumentException("Cookie 不能为空");
        }
        try {
            Files.writeString(ctx.cookieFile(platform), cookie.trim());
        } catch (Exception e) {
            throw new IllegalStateException("写 Cookie 失败: " + e.getMessage(), e);
        }
        return Map.of("saved", true, "platform", platform, "cookies", cookieStatuses());
    }

    /**
     /** 手动重建全文索引（后台跑，立即返回） */
     public Map<String, Object> reindex() {
         Integer msgs = ctx.jdbc().queryForObject("SELECT COUNT(*) FROM message", Integer.class);
         int n = msgs == null ? 0 : msgs;
         com.lion.chatexporter.repository.Schema.rebuildAsync(ctx.jdbc(), n);
         return Map.of("started", true, "messages", n);
     }

     /** 在线体检：真的拿 Cookie 打一次轻量请求。
     * 豆包没有公开的"会话信息"接口，这里跳过（它有过期时间可本地解析）；
     * ChatGPT 可以打 /api/auth/session，能顺手拿到 plan 与过期时间。
     */
    public Map<String, Object> checkOnline() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (ChatPlatformAdapter a : adapters) {
            Path f = ctx.cookieFile(a.platformId());
            if (!Files.exists(f)) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("platform", a.platformId());
            try {
                String cookie = Files.readString(f).trim();
                AdapterContext c = new AdapterContext(cookie, 0);
                if (a instanceof OpenAiAdapter oa) {
                    Map<String, Object> s = oa.probeSession(c);
                    r.put("status", "ok");
                    r.put("plan", s.get("plan"));
                    r.put("expiresAt", s.get("expires"));
                } else {
                    r.put("status", "skipped");
                    r.put("note", "该平台本地即可算出过期时间");
                }
            } catch (Exception e) {
                r.put("status", "error");
                r.put("error", e.getMessage());
            }
            results.add(r);
        }
        return Map.of("results", results);
    }
}
