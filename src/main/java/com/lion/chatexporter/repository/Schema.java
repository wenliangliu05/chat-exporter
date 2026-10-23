package com.lion.chatexporter.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 建表 + 两套全文索引 + 轻量迁移。
 *
 * <h3>为什么是两套索引</h3>
 * <ul>
 *   <li><b>trigram 索引</b>（{@code message_fts}）：按三字滑窗建倒排，中文子串匹配、
 *       零分词依赖。缺点：<b>查询串必须 ≥3 个字符</b>，2 字（"内存""索引"）无论如何都命中不了。</li>
 *   <li><b>字符索引</b>（{@code message_fts_char}）：把正文拆成"单字 + 空格"
 *       （{@code 内 存 泄 漏}），用 unicode61 分词后每个字是一个 token，
 *       于是 {@code "内 存"} 就成了<b>短语查询</b>——2 字也能走索引。</li>
 * </ul>
 *
 * <p>两套索引都用 external content 模式（不复制正文），代价只是 2 字查询才用到的
 * {@code chars} 辅助列。删除/更新靠触发器镜像，保证索引不腐化。
 *
 * <p>索引重建可能很慢（2 万条 trigram 约 100 秒），一律丢后台线程，不阻塞启动。
 */
public final class Schema {

    private static final Logger log = LoggerFactory.getLogger(Schema.class);

    private Schema() {}

    public static void init(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS snapshot (
                  id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                  platform           TEXT NOT NULL,
                  account_id         TEXT,
                  label              TEXT,
                  created_at         TEXT NOT NULL,
                  conversation_count INTEGER DEFAULT 0,
                  message_count      INTEGER DEFAULT 0,
                  duration_ms        INTEGER DEFAULT 0,
                  char_count         INTEGER DEFAULT 0,
                  base_snapshot_id   INTEGER,
                  note               TEXT
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS conversation (
                  id            INTEGER PRIMARY KEY AUTOINCREMENT,
                  snapshot_id   INTEGER NOT NULL,
                  conv_key      TEXT NOT NULL,
                  title         TEXT,
                  message_count INTEGER DEFAULT 0,
                  md_path       TEXT,
                  UNIQUE(snapshot_id, conv_key)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS message (
                  id              INTEGER PRIMARY KEY AUTOINCREMENT,
                  conversation_id INTEGER NOT NULL,
                  seq             INTEGER NOT NULL,
                  role            TEXT,
                  kind            TEXT,
                  content         TEXT,
                  chars           TEXT,
                  created_at      TEXT
                )""");

        // 老库平滑升级
        ensureColumn(jdbc, "snapshot", "duration_ms", "duration_ms INTEGER DEFAULT 0");
        ensureColumn(jdbc, "snapshot", "char_count", "char_count INTEGER DEFAULT 0");
        ensureColumn(jdbc, "snapshot", "base_snapshot_id", "base_snapshot_id INTEGER");
        ensureColumn(jdbc, "message", "chars", "chars TEXT");

        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_conv_snapshot ON conversation(snapshot_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_msg_conv ON message(conversation_id)");

        // ---------- 索引一：trigram（≥3 字）----------
        jdbc.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                  content, content='message', content_rowid='id', tokenize='trigram')""");
        jdbc.execute("""
                CREATE TRIGGER IF NOT EXISTS trg_msg_ai AFTER INSERT ON message BEGIN
                  INSERT INTO message_fts(rowid, content) VALUES (new.id, new.content);
                END""");
        jdbc.execute("""
                CREATE TRIGGER IF NOT EXISTS trg_msg_ad AFTER DELETE ON message BEGIN
                  INSERT INTO message_fts(message_fts, rowid, content) VALUES('delete', old.id, old.content);
                END""");
        // 只关心 content 的变更：补 chars 列时不该把整篇正文重新索引一遍
        jdbc.execute("DROP TRIGGER IF EXISTS trg_msg_au");
        jdbc.execute("""
                CREATE TRIGGER trg_msg_au AFTER UPDATE OF content ON message BEGIN
                  INSERT INTO message_fts(message_fts, rowid, content) VALUES('delete', old.id, old.content);
                  INSERT INTO message_fts(rowid, content) VALUES (new.id, new.content);
                END""");

        // ---------- 索引二：字符级（2 字）----------
        jdbc.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS message_fts_char USING fts5(
                  chars, content='message', content_rowid='id', tokenize='unicode61')""");
        jdbc.execute("""
                CREATE TRIGGER IF NOT EXISTS trg_msg_c_ai AFTER INSERT ON message BEGIN
                  INSERT INTO message_fts_char(rowid, chars) VALUES (new.id, new.chars);
                END""");
        jdbc.execute("""
                CREATE TRIGGER IF NOT EXISTS trg_msg_c_ad AFTER DELETE ON message BEGIN
                  INSERT INTO message_fts_char(message_fts_char, rowid, chars) VALUES('delete', old.id, old.chars);
                END""");
        jdbc.execute("DROP TRIGGER IF EXISTS trg_msg_c_au");
        jdbc.execute("""
                CREATE TRIGGER trg_msg_c_au AFTER UPDATE OF chars ON message BEGIN
                  INSERT INTO message_fts_char(message_fts_char, rowid, chars) VALUES('delete', old.id, old.chars);
                  INSERT INTO message_fts_char(rowid, chars) VALUES (new.id, new.chars);
                END""");

        // ---------- 索引健康检查 ----------
        Integer msgs = jdbc.queryForObject("SELECT COUNT(*) FROM message", Integer.class);
        int n = msgs == null ? 0 : msgs;

        int trigramRows = safeCount(jdbc, "SELECT COUNT(*) FROM message_fts_idx");
        if (n > 0 && trigramRows == 0) {
            rebuildTrigramAsync(jdbc, n);
        }

        Integer missingChars = jdbc.queryForObject(
                "SELECT COUNT(*) FROM message WHERE chars IS NULL AND content IS NOT NULL", Integer.class);
        if (missingChars != null && missingChars > 0) {
            backfillCharsAsync(jdbc, missingChars);
        }
    }

    /**
     * 正文 → "单字 + 空格"。
     *
     * <p>这是字符级索引能工作的前提：unicode61 会把连续的中文当成一个 token，
     * 只有人为插入空格，才能让"每个字一个 token"，从而支持 {@code "内 存"} 短语查询。
     * 空白字符直接丢掉（否则会切出空 token）。
     */
    public static String toChars(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            sb.append(c).append(' ');
        }
        return sb.toString();
    }

    /** 重建 trigram 索引（后台，大库约 1~2 分钟） */
    public static void rebuildTrigramAsync(JdbcTemplate jdbc, int msgCount) {
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            log.info("全文索引为空，后台重建 trigram（{} 条消息）…", msgCount);
            try {
                jdbc.execute("INSERT INTO message_fts(message_fts) VALUES('rebuild')");
                log.info("trigram 索引重建完成，用时 {} ms", System.currentTimeMillis() - t0);
            } catch (Exception e) {
                log.error("trigram 索引重建失败: {}", e.getMessage(), e);
            }
        }, "fts-rebuild");
        t.setDaemon(true);
        t.start();
    }

    /** 补历史消息的 chars 列，然后重建字符索引（后台） */
    public static void backfillCharsAsync(JdbcTemplate jdbc, int missing) {
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            log.info("检测到 {} 条历史消息缺少字符索引，后台补齐…", missing);
            int done = 0;
            try {
                while (true) {
                    List<Map<String, Object>> rows = jdbc.queryForList(
                            "SELECT id, content FROM message WHERE chars IS NULL AND content IS NOT NULL LIMIT 500");
                    if (rows.isEmpty()) {
                        break;
                    }
                    List<Object[]> args = new ArrayList<>(rows.size());
                    for (Map<String, Object> r : rows) {
                        args.add(new Object[]{toChars((String) r.get("content")), r.get("id")});
                    }
                    jdbc.batchUpdate("UPDATE message SET chars = ? WHERE id = ?", args);
                    done += rows.size();
                    if (done % 5000 == 0) {
                        log.info("字符索引补齐进度: {}", done);
                    }
                }
                jdbc.execute("INSERT INTO message_fts_char(message_fts_char) VALUES('rebuild')");
                log.info("字符索引就绪：{} 条，用时 {} ms", done, System.currentTimeMillis() - t0);
            } catch (Exception e) {
                log.error("字符索引补齐失败: {}", e.getMessage(), e);
            }
        }, "fts-char-backfill");
        t.setDaemon(true);
        t.start();
    }

    private static int safeCount(JdbcTemplate jdbc, String sql) {
        try {
            Integer v = jdbc.queryForObject(sql, Integer.class);
            return v == null ? 0 : v;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void ensureColumn(JdbcTemplate jdbc, String table, String column, String ddl) {
        var cols = jdbc.queryForList("PRAGMA table_info(" + table + ")");
        boolean exists = cols.stream().anyMatch(c -> column.equalsIgnoreCase(String.valueOf(c.get("name"))));
        if (!exists) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + ddl);
        }
    }
}
