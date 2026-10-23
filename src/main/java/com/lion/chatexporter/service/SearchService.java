package com.lion.chatexporter.service;

import com.lion.chatexporter.config.ProfileContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全文检索（SQLite FTS5 + trigram 分词）。
 *
 * <p><b>为什么用 trigram</b>：中文没有词边界，默认分词器会把整句当一个 token，
 * 搜"内存"永远搜不到。trigram 按三字滑窗建倒排，天然支持中文子串，也不需要外部分词库。
 *
 * <p><b>可用性设计</b>（三点）：
 * <ol>
 *   <li>作用域：可限定到某个快照（= 某平台某次导出），也可按平台过滤</li>
 *   <li>降级：FTS 抛异常（索引损坏 / 版本不兼容）自动退回 LIKE，功能不中断</li>
 *   <li>短词：不足 3 字 trigram 无法匹配，直接走 LIKE</li>
 * </ol>
 *
 * <p>返回的片段用 \u0001 / \u0002 包住命中词，由前端转义后再换成 &lt;mark&gt;，
 * 避免消息正文里的 HTML 被当作标记执行。
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final ProfileContext ctx;

    public SearchService(ProfileContext ctx) {
        this.ctx = ctx;
    }

    public Map<String, Object> search(String q, Long snapshotId, String platform, int limit) {
        long t0 = System.currentTimeMillis();
        String query = q == null ? "" : q.trim();
        String plat = (platform == null || platform.isBlank() || "all".equals(platform)) ? null : platform;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("snapshotId", snapshotId);
        out.put("platform", plat);
        if (query.isEmpty()) {
            out.put("total", 0);
            out.put("returned", 0);
            out.put("items", List.of());
            return out;
        }

        JdbcTemplate jdbc = ctx.jdbc();
        int cap = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        List<Map<String, Object>> rows;
        Integer total;
        String mode;

        if (query.length() < 3) {
            try {
                // 2 字：字符索引 + 短语查询（"内 存"），比全表 LIKE 快两个数量级
                mode = "char-index(短词)";
                String phrase = charPhrase(query);
                rows = charQuery(jdbc, phrase, query, snapshotId, plat, cap);
                total = charCount(jdbc, phrase, snapshotId, plat);
            } catch (Exception e) {
                log.warn("字符索引不可用，短词降级 LIKE（{}）", e.getMessage());
                mode = "like(降级)";
                rows = likeQuery(jdbc, query, snapshotId, plat, cap);
                total = likeCount(jdbc, query, snapshotId, plat);
            }
        } else {
            try {
                mode = "fts";
                rows = ftsQuery(jdbc, query, snapshotId, plat, cap);
                total = ftsCount(jdbc, query, snapshotId, plat);
            } catch (Exception e) {
                // 索引损坏 / trigram 不可用 → 自动降级，保证搜索"还能用"
                log.warn("FTS 查询失败，已降级为 LIKE（{}）", e.getMessage());
                mode = "like(降级)";
                rows = likeQuery(jdbc, query, snapshotId, plat, cap);
                total = likeCount(jdbc, query, snapshotId, plat);
            }
        }

        out.put("mode", mode);
        out.put("total", total == null ? rows.size() : total);
        out.put("returned", rows.size());
        out.put("tookMs", System.currentTimeMillis() - t0);
        out.put("items", rows);
        return out;
    }

    // ---------------- FTS 路径 ----------------

    private List<Map<String, Object>> ftsQuery(JdbcTemplate jdbc, String q, Long snapshotId, String plat, int cap) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.seq, m.role, m.kind, m.conversation_id, c.title,
                       c.snapshot_id, s.platform,
                       snippet(message_fts, 0, char(1), char(2), '…', 24) AS snip
                FROM message_fts
                JOIN message m ON m.id = message_fts.rowid
                JOIN conversation c ON c.id = m.conversation_id
                JOIN snapshot s ON s.id = c.snapshot_id
                WHERE message_fts MATCH ?
                """);
        List<Object> args = new ArrayList<>();
        args.add("\"" + q.replace("\"", "") + "\"");
        appendScope(sql, args, snapshotId, plat);
        sql.append(" ORDER BY rank LIMIT ?");
        args.add(cap);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private Integer ftsCount(JdbcTemplate jdbc, String q, Long snapshotId, String plat) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM message_fts
                JOIN message m ON m.id = message_fts.rowid
                JOIN conversation c ON c.id = m.conversation_id
                JOIN snapshot s ON s.id = c.snapshot_id
                WHERE message_fts MATCH ?
                """);
        List<Object> args = new ArrayList<>();
        args.add("\"" + q.replace("\"", "") + "\"");
        appendScope(sql, args, snapshotId, plat);
        return jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
    }

    // ---------------- 字符索引路径（<3 字） ----------------

    /** "内存" → "\"内 存\""：FTS5 短语查询，要求两个字相邻出现 */
    private String charPhrase(String q) {
        StringBuilder sb = new StringBuilder(q.length() * 2 + 2).append('"');
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (sb.length() > 1) {
                sb.append(' ');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }

    private List<Map<String, Object>> charQuery(JdbcTemplate jdbc, String phrase, String raw,
                                                Long snapshotId, String plat, int cap) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.seq, m.role, m.kind, m.conversation_id, c.title,
                       c.snapshot_id, s.platform,
                       CASE WHEN instr(m.content, ?) > 0
                            THEN substr(m.content, max(1, instr(m.content, ?) - 50), 180)
                            ELSE substr(m.content, 1, 180) END AS snip
                FROM message_fts_char
                JOIN message m ON m.id = message_fts_char.rowid
                JOIN conversation c ON c.id = m.conversation_id
                JOIN snapshot s ON s.id = c.snapshot_id
                WHERE message_fts_char MATCH ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(raw);
        args.add(raw);
        args.add(phrase);
        appendScope(sql, args, snapshotId, plat);
        sql.append(" ORDER BY rank LIMIT ?");
        args.add(cap);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private Integer charCount(JdbcTemplate jdbc, String phrase, Long snapshotId, String plat) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM message_fts_char
                JOIN message m ON m.id = message_fts_char.rowid
                JOIN conversation c ON c.id = m.conversation_id
                JOIN snapshot s ON s.id = c.snapshot_id
                WHERE message_fts_char MATCH ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(phrase);
        appendScope(sql, args, snapshotId, plat);
        return jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
    }

    // ---------------- LIKE 路径（降级兜底） ----------------

    private List<Map<String, Object>> likeQuery(JdbcTemplate jdbc, String q, Long snapshotId, String plat, int cap) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.seq, m.role, m.kind, m.conversation_id, c.title,
                       c.snapshot_id, s.platform,
                       CASE WHEN instr(m.content, ?) > 0
                            THEN substr(m.content, max(1, instr(m.content, ?) - 50), 180)
                            ELSE substr(m.content, 1, 180) END AS snip
                FROM message m
                JOIN conversation c ON c.id = m.conversation_id
                JOIN snapshot s ON s.id = c.snapshot_id
                WHERE m.content LIKE ? ESCAPE '\\'
                """);
        List<Object> args = new ArrayList<>();
        args.add(q);
        args.add(q);
        args.add("%" + escapeLike(q) + "%");
        appendScope(sql, args, snapshotId, plat);
        sql.append(" ORDER BY m.id DESC LIMIT ?");
        args.add(cap);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private Integer likeCount(JdbcTemplate jdbc, String q, Long snapshotId, String plat) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM message m
                JOIN conversation c ON c.id = m.conversation_id
                JOIN snapshot s ON s.id = c.snapshot_id
                WHERE m.content LIKE ? ESCAPE '\\'
                """);
        List<Object> args = new ArrayList<>();
        args.add("%" + escapeLike(q) + "%");
        appendScope(sql, args, snapshotId, plat);
        return jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
    }

    // ---------------- 共用 ----------------

    private void appendScope(StringBuilder sql, List<Object> args, Long snapshotId, String plat) {
        if (snapshotId != null) {
            sql.append(" AND c.snapshot_id = ?");
            args.add(snapshotId);
        }
        if (plat != null) {
            sql.append(" AND s.platform = ?");
            args.add(plat);
        }
    }

    private String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
