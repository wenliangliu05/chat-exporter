package com.lion.chatexporter.domain;

import java.time.Instant;

/**
 * 业务模型第 4 层（叶子）：一条消息。
 *
 * <p>record = 不可变数据载体。消息一旦从平台拉下来就不该再改，
 * 用 record 能让"只读"这件事由语言保证。
 *
 * @param seq    平台内的序号（豆包的 index），增量导出的锚点
 * @param role   user / assistant / system
 * @param kind   text / thinking / tool / attachment / unknown —— 渲染时用来分级展示
 * @param content 已提取的纯文本（复杂块在适配器里就拍平，领域层不关心平台细节）
 */
public record Message(
        long seq,
        String role,
        String kind,
        String content,
        Instant createdAt
) {
    public static Message of(long seq, String role, String kind, String content) {
        return new Message(seq, role, kind, content, Instant.now());
    }
}
