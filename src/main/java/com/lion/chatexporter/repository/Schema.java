package com.lion.chatexporter.repository;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 建表 + FTS5 全文索引 + 轻量迁移。
 *
 * <p>全文索引用 <b>FTS5 的 trigram 分词器</b>：
 * 中文没有空格，标准分词器会把整句当一个 token，搜"内存"搜不到；
 * trigram 按三字滑窗建索引，天然支持中文子串匹配，也不需要外部分词库。
 * 代价是查询串至少 3 个字符（2 字查询走 LIKE 兜底）。
 *
 * <p>用 external content 模式（content='message'）而不是复制一份正文：
 * 索引只存倒排，正文仍只有一份，省空间也不怕不同步。
 */
public final class Schema {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Schema.class);

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
                  created_at      TEXT
                )""");

        // 老库平滑升级
        ensureColumn(jdbc, "snapshot", "duration_ms", "duration_ms INTEGER DEFAULT 0");
        ensureColumn(jdbc, "snapshot", "char_count", "char_count INTEGER DEFAULT 0");
        ensureColumn(jdbc, "snapshot", "base_snapshot_id", "base_snapshot_id INTEGER");

        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_conv_snapshot ON conversation(snapshot_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_msg_conv ON message(conversation_id)");

        // ---- 全文索引 ----
        jdbc.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                  content,
                  content='message',
                  content_rowid='id',
                  tokenize='trigram'
                )""");
        jdbc.execute("""
                CREATE TRIGGER IF NOT EXISTS trg_msg_ai AFTER INSERT ON message BEGIN
                  INSERT INTO message_fts(rowid, content) VALUES (new.id, new.content);
                END""");
        jdbc.execute("""
                CREATE TRIGGER IF NOT EXISTS trg_msg_ad AFTER DELETE ON message BEGIN
                  INSERT INTO message_fts(message_fts, rowid, content) VALUES('delete', old.id, old.content);
                END""");
        jdbc.execute("""
                CREATE TRIGGER IF NOT EXISTS trg_msg_au AFTER UPDATE ON message BEGIN
                  INSERT INTO message_fts(message_fts, rowid, content) VALUES('delete', old.id, old.content);
                  INSERT INTO message_fts(rowid, content) VALUES (new.id, new.content);
                END""");

        // 历史数据补索引。
        //
        // 坑 1：external-content 的 FTS 表，COUNT(*) 读的是**原表**(message)，不是倒排索引。
        //       所以只能用影子表判断：message_fts_idx 为 0 行 = 一条都没索引过。
        // 坑 2：2 万条消息建 trigram 索引要 ~100 秒。绝不能在启动流程里同步做，
        //       否则应用起不来、前端以为"没数据"。这里丢到后台线程，接口立即可用。
        Integer msgs = jdbc.queryForObject("SELECT COUNT(*) FROM message", Integer.class);
        int idxRows = 0;
        try {
            idxRows = jdbc.queryForObject("SELECT COUNT(*) FROM message_fts_idx", Integer.class);
        } catch (Exception e) {
            idxRows = 0;
        }
        if (msgs != null && msgs > 0 && idxRows == 0) {
            rebuildAsync(jdbc, msgs);
        }
    }

    /** 后台重建全文索引（大库可能要一两分钟，不阻塞启动） */
    public static void rebuildAsync(JdbcTemplate jdbc, int msgCount) {
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            log.info("全文索引为空，后台开始重建（{} 条消息，预计较慢）…", msgCount);
            try {
                jdbc.execute("INSERT INTO message_fts(message_fts) VALUES('rebuild')");
                log.info("全文索引重建完成，用时 {} ms", System.currentTimeMillis() - t0);
            } catch (Exception e) {
                log.error("全文索引重建失败: {}", e.getMessage(), e);
            }
        }, "fts-rebuild");
        t.setDaemon(true);
        t.start();
    }

    private static void ensureColumn(JdbcTemplate jdbc, String table, String column, String ddl) {
        var cols = jdbc.queryForList("PRAGMA table_info(" + table + ")");
        boolean exists = cols.stream().anyMatch(c -> column.equalsIgnoreCase(String.valueOf(c.get("name"))));
        if (!exists) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + ddl);
        }
    }
}
