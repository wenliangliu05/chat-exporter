package com.lion.chatexporter.repository;

import com.lion.chatexporter.config.ProfileContext;
import com.lion.chatexporter.domain.Conversation;
import com.lion.chatexporter.domain.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 持久化层：只管 SQL。
 *
 * <p>注意 {@code jdbc()} 是<b>每次取当前 profile 的 JdbcTemplate</b>，
 * 所以切换 profile 之后，后续写入自动落到新库，不用重启。
 *
 * <p>SQLite 单写者：写方法统一 synchronized。
 */
@Repository
public class SnapshotRepository {

    private final ProfileContext ctx;

    public SnapshotRepository(ProfileContext ctx) {
        this.ctx = ctx;
    }

    private JdbcTemplate jdbc() {
        return ctx.jdbc();
    }

    // ---------------- 写 ----------------

    public synchronized long createSnapshot(String platform, String accountId, String label,
                                            Long baseSnapshotId, String note) {
        JdbcTemplate j = jdbc();
        KeyHolder kh = new GeneratedKeyHolder();
        j.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO snapshot(platform, account_id, label, created_at, base_snapshot_id, note) "
                    + "VALUES (?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, platform);
            ps.setString(2, accountId);
            ps.setString(3, label);
            ps.setString(4, Instant.now().toString());
            if (baseSnapshotId == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setLong(5, baseSnapshotId);
            }
            ps.setString(6, note);
            return ps;
        }, kh);
        return kh.getKey() == null ? 0 : kh.getKey().longValue();
    }

    public synchronized long createConversation(long snapshotId, Conversation c, String mdPath) {
        JdbcTemplate j = jdbc();
        KeyHolder kh = new GeneratedKeyHolder();
        j.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT OR REPLACE INTO conversation(snapshot_id, conv_key, title, message_count, md_path) "
                    + "VALUES (?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, snapshotId);
            ps.setString(2, c.convKey());
            ps.setString(3, c.title());
            ps.setInt(4, c.messageCount());
            ps.setString(5, mdPath);
            return ps;
        }, kh);
        return kh.getKey() == null ? 0 : kh.getKey().longValue();
    }

    public synchronized void insertMessages(long convRowId, List<Message> messages) {
        if (messages.isEmpty()) {
            return;
        }
        jdbc().batchUpdate(
                "INSERT INTO message(conversation_id, seq, role, kind, content, chars, created_at) "
                + "VALUES (?,?,?,?,?,?,?)",
                messages, 500, (ps, m) -> {
                    ps.setLong(1, convRowId);
                    ps.setLong(2, m.seq());
                    ps.setString(3, m.role());
                    ps.setString(4, m.kind());
                    ps.setString(5, m.content());
                    ps.setString(6, Schema.toChars(m.content()));   // 字符级索引用的辅助列
                    ps.setString(7, Instant.now().toString());
                });
    }

    public synchronized int copyMessages(long newConvRowId, long oldConvRowId) {
        return jdbc().update("""
                INSERT INTO message(conversation_id, seq, role, kind, content, chars, created_at)
                SELECT ?, seq, role, kind, content, chars, created_at
                FROM message WHERE conversation_id=? ORDER BY seq""",
                newConvRowId, oldConvRowId);
    }

    public synchronized void updateConversation(long rowId, int messageCount, String mdPath) {
        jdbc().update("UPDATE conversation SET message_count=?, md_path=? WHERE id=?",
                messageCount, mdPath, rowId);
    }

    public synchronized void updateSnapshotStats(long snapshotId, int convCount, int msgCount,
                                                 long durationMs, long chars) {
        jdbc().update("UPDATE snapshot SET conversation_count=?, message_count=?, duration_ms=?, char_count=? WHERE id=?",
                convCount, msgCount, durationMs, chars, snapshotId);
    }

    // ---------------- 读 ----------------

    public List<Map<String, Object>> findSnapshots() {
        return jdbc().queryForList("""
                SELECT id, platform, label, created_at, conversation_count, message_count,
                       duration_ms, char_count, base_snapshot_id
                FROM snapshot ORDER BY id DESC""");
    }

    public Map<String, Object> findSnapshot(long id) {
        List<Map<String, Object>> rows = jdbc().queryForList("SELECT * FROM snapshot WHERE id=?", id);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public List<Map<String, Object>> findConversations(long snapshotId) {
        return jdbc().queryForList("""
                SELECT id, conv_key, title, message_count, md_path
                FROM conversation WHERE snapshot_id=? ORDER BY id""", snapshotId);
    }

    /**
     * 消息分页：以 {@code id} 作游标（seq 会重复：思考/正文/工具共享同一 seq）。
     *
     * @param beforeId 只取 id < beforeId 的更早消息；null = 取最新一页
     */
    public Map<String, Object> pageMessages(long convRowId, Long beforeId, int limit) {
        int cap = Math.max(1, Math.min(limit <= 0 ? 40 : limit, 200));
        String sql = "SELECT id, seq, role, kind, content FROM message WHERE conversation_id=?"
                + (beforeId == null ? "" : " AND id < ?")
                + " ORDER BY id DESC LIMIT ?";
        List<Map<String, Object>> rows = beforeId == null
                ? jdbc().queryForList(sql, convRowId, cap)
                : jdbc().queryForList(sql, convRowId, beforeId, cap);

        boolean hasMore = false;
        if (!rows.isEmpty()) {
            long minId = rows.stream()
                    .mapToLong(r -> ((Number) r.get("id")).longValue())
                    .min().orElse(0L);
            Integer older = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM message WHERE conversation_id=? AND id<?", Integer.class, convRowId, minId);
            hasMore = older != null && older > 0;
            java.util.Collections.reverse(rows);   // 返回时按时间正序
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows);
        out.put("hasMore", hasMore);
        out.put("total", countMessages(convRowId));
        return out;
    }

    public List<Map<String, Object>> findMessages(long convRowId) {
        return jdbc().queryForList("""
                SELECT seq, role, kind, content FROM message
                WHERE conversation_id=? ORDER BY seq, id""", convRowId);
    }

    public Long findConversationRow(long snapshotId, String convKey) {
        List<Long> ids = jdbc().queryForList(
                "SELECT id FROM conversation WHERE snapshot_id=? AND conv_key=?",
                Long.class, snapshotId, convKey);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public Map<String, Long> conversationRows(long snapshotId) {
        Map<String, Long> map = new LinkedHashMap<>();
        jdbc().query("SELECT conv_key, id FROM conversation WHERE snapshot_id=?", rs -> {
            map.put(rs.getString("conv_key"), rs.getLong("id"));
        }, snapshotId);
        return map;
    }

    public long maxSeq(long convRowId) {
        Long v = jdbc().queryForObject(
                "SELECT COALESCE(MAX(seq), -1) FROM message WHERE conversation_id=?", Long.class, convRowId);
        return v == null ? -1 : v;
    }

    public int countMessages(long convRowId) {
        Integer v = jdbc().queryForObject(
                "SELECT COUNT(*) FROM message WHERE conversation_id=?", Integer.class, convRowId);
        return v == null ? 0 : v;
    }

    public int countConversations(long snapshotId) {
        Integer v = jdbc().queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE snapshot_id=?", Integer.class, snapshotId);
        return v == null ? 0 : v;
    }

    public long[] stats(long snapshotId) {
        Map<String, Object> row = jdbc().queryForMap("""
                SELECT COUNT(*) AS n,
                       COALESCE(SUM(LENGTH(content)), 0) AS total_chars,
                       COALESCE(SUM(CASE WHEN kind='text'
                            THEN LENGTH(REPLACE(REPLACE(REPLACE(content, char(10), ''), char(13), ''), ' ', ''))
                            ELSE 0 END), 0) AS eff_chars
                FROM message
                WHERE conversation_id IN (SELECT id FROM conversation WHERE snapshot_id=?)""", snapshotId);
        return new long[]{
                ((Number) row.get("n")).longValue(),
                ((Number) row.get("total_chars")).longValue(),
                ((Number) row.get("eff_chars")).longValue()
        };
    }
}
