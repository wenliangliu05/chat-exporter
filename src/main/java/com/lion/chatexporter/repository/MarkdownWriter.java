package com.lion.chatexporter.repository;

import com.lion.chatexporter.config.ProfileContext;
import com.lion.chatexporter.domain.Conversation;
import com.lion.chatexporter.domain.Message;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 会话 → Markdown 文件。
 *
 * <p>两种入口：
 * ① {@link #write} 用内存里的领域对象（全量导出时顺路写）；
 * ② {@link #writeFromRows} 从数据库行重渲染（增量导出时，历史 + 新增要合成一个文件）。
 */
@Component
public class MarkdownWriter {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ProfileContext ctx;

    public MarkdownWriter(ProfileContext ctx) {
        this.ctx = ctx;
    }

    public String write(long snapshotId, Conversation conv) {
        return render(snapshotId, conv.convKey(), conv.title(), conv.messages().stream()
                .map(m -> new Row(m.seq(), m.role(), m.kind(), m.content()))
                .toList());
    }

    public String writeFromRows(long snapshotId, String convKey, String title, List<Map<String, Object>> rows) {
        List<Row> list = rows.stream()
                .map(r -> new Row(
                        ((Number) r.get("seq")).longValue(),
                        String.valueOf(r.get("role")),
                        String.valueOf(r.get("kind")),
                        String.valueOf(r.get("content"))))
                .toList();
        return render(snapshotId, convKey, title, list);
    }

    private record Row(long seq, String role, String kind, String content) {}

    private String render(long snapshotId, String convKey, String title, List<Row> rows) {
        Path file = Path.of(ctx.markdownDir().toString(), "snapshot-" + snapshotId,
                sanitize(title) + "-" + sanitize(convKey) + ".md");

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n")
          .append("- 会话 ID：`").append(convKey).append("`\n")
          .append("- 消息数：").append(rows.size()).append("\n")
          .append("- 导出时间：").append(FMT.format(Instant.now())).append("\n\n---\n\n");

        String lastRole = null;
        for (Row m : rows) {
            if (!m.role().equals(lastRole)) {
                sb.append("\n**").append("user".equalsIgnoreCase(m.role()) ? "🙋 我" : "🤖 助手")
                  .append("**\n\n");
                lastRole = m.role();
            }
            switch (m.kind()) {
                case "thinking" -> sb.append("> 💭 *思考*\n> ").append(indent(m.content())).append("\n\n");
                case "tool" -> sb.append("`🔧 工具` ").append(oneLine(m.content())).append("\n\n");
                case "attachment" -> sb.append("📎 ").append(oneLine(m.content())).append("\n\n");
                case "search" -> sb.append("🔍 ").append(oneLine(m.content())).append("\n\n");
                default -> sb.append(m.content()).append("\n\n");
            }
        }

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写 Markdown 失败: " + file, e);
        }
        return ctx.markdownDir() + "/snapshot-" + snapshotId + "/" + file.getFileName();
    }

    private String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private String indent(String s) {
        return s == null ? "" : s.replace("\n", "\n> ");
    }

    private String sanitize(String s) {
        if (s == null || s.isBlank()) {
            return "untitled";
        }
        String cleaned = s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }
}
