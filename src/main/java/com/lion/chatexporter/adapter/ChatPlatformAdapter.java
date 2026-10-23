package com.lion.chatexporter.adapter;

import com.lion.chatexporter.domain.Conversation;
import com.lion.chatexporter.domain.Message;

import java.util.List;

/**
 * 工具模型的统一出口（六边形架构里的"端口"）。
 *
 * <p>为什么要有这一层？
 * 业务层只认识"列会话 / 拉消息"这两件事，不认识 HTTP、Cookie、JSON 字段。
 * 将来接 ChatGPT / Claude / Kimi，只要再写一个实现，业务层一行都不用改。
 * 这就是"可插拔"在代码里的样子。
 */
public interface ChatPlatformAdapter {

    /** 平台标识，与 Platform.code() 对应 */
    String platformId();

    /** 列出账号下所有会话（只带标题等元信息，不含消息） */
    List<Conversation> listConversations(AdapterContext ctx);

    /**
     * 拉一个会话的消息。
     *
     * @param sinceSeq 增量锚点：只返回 seq &gt; sinceSeq 的消息；全量传 -1
     */
    List<Message> fetchMessages(AdapterContext ctx, String convKey, long sinceSeq);
}
