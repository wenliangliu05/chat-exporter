package com.lion.chatexporter.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务模型第 3 层：会话（宿主持有消息，形成树）。
 *
 * <p>你问的"每层通过树状结构持有下层对象叫什么"——
 * 这叫<b>聚合（Aggregate）</b>：Snapshot 是聚合根，Conversation / Message 是内部实体，
 * 外部只能通过聚合根拿到它们，保证一致性边界清晰（导出一次 = 一个聚合）。
 */
public class Conversation {

    private final String convKey;      // 平台内唯一 ID
    private String title;
    private long positionVersion;      // 豆包列表翻页锚点，别的平台可忽略
    private final List<Message> messages = new ArrayList<>();

    public Conversation(String convKey, String title) {
        this.convKey = convKey;
        this.title = title;
    }

    public String convKey() {
        return convKey;
    }

    public String title() {
        return title;
    }

    public void rename(String newTitle) {
        if (newTitle != null && !newTitle.isBlank()) {
            this.title = newTitle;
        }
    }

    public long positionVersion() {
        return positionVersion;
    }

    public void positionVersion(long v) {
        this.positionVersion = v;
    }

    public List<Message> messages() {
        return messages;
    }

    public void addMessages(List<Message> batch) {
        this.messages.addAll(batch);
    }

    public int messageCount() {
        return messages.size();
    }
}
