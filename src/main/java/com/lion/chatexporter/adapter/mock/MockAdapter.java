package com.lion.chatexporter.adapter.mock;

import com.lion.chatexporter.adapter.AdapterContext;
import com.lion.chatexporter.adapter.ChatPlatformAdapter;
import com.lion.chatexporter.domain.Conversation;
import com.lion.chatexporter.domain.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟平台适配器。
 *
 * <p>它的价值不是"造假数据"，而是<b>让整条流水线可测试、可演示</b>：
 * 没有 Cookie、没有网络时，依然能验证 建快照 → 并发抓取 → 落库 → 写 Markdown → 前端展示 全链路。
 * 这也是"适配器隔离"带来的好处——换实现不影响任何上层代码。
 */
@Component
public class MockAdapter implements ChatPlatformAdapter {

    private static final String[][] TOPICS = {
            {"HJ113 变化的数组 解题思路", "这道题的关键是把'随机加'看成 bit 翻转的马尔可夫过程……"},
            {"Spring Boot 4 迁移笔记", "starter-web 改名为 starter-webmvc，Jackson 换成了 3.x……"},
            {"WSL2 与 Windows 端口互通", "mirrored 模式下 Windows 和 WSL 共享端口，所以 8787 会互相抢……"},
            {"本地大模型选型", "10GB 显存要跑 3 万上下文，FP8 权重 9.4GB 装不下，得用 INT4……"},
            {"SQLite 并发写入", "单写者模型，多线程写会 database is locked，最省心的做法是把连接池压到 1……"},
            {"RabbitMQ 在 Windows 上的坑", "微信占用了 4369，epmd 必须换端口，否则 broker 起不来……"},
    };

    @Override
    public String platformId() {
        return "mock";
    }

    @Override
    public List<Conversation> listConversations(AdapterContext ctx) {
        sleep(Math.min(200, ctx.requestIntervalMs()));
        List<Conversation> list = new ArrayList<>();
        for (int i = 0; i < TOPICS.length; i++) {
            Conversation c = new Conversation("mock-" + (1000 + i), TOPICS[i][0]);
            c.positionVersion(2_000_000_000_000L - i);
            list.add(c);
        }
        return list;
    }

    @Override
    public List<Message> fetchMessages(AdapterContext ctx, String convKey, long sinceSeq) {
        sleep(Math.min(300, ctx.requestIntervalMs()));
        int idx = Integer.parseInt(convKey.replace("mock-", "")) - 1000;
        String[] topic = TOPICS[Math.min(idx, TOPICS.length - 1)];

        List<Message> out = new ArrayList<>();
        long seq = 0;
        // 每个会话 12 条：思考 → 正文 交替，模拟真实结构
        for (int round = 0; round < 3; round++) {
            out.add(Message.of(++seq, "user", "text",
                    round == 0 ? topic[0] + "\n能详细讲讲吗？" : "继续，那第 " + (round + 1) + " 个问题呢？"));
            out.add(Message.of(++seq, "assistant", "thinking",
                    "先确认问题的前提，再给出结论；如果涉及数字，最好算一遍再回答。"));
            out.add(Message.of(++seq, "assistant", "text",
                    topic[1] + "\n\n（第 " + (round + 1) + " 轮回答，用于验证多轮对话的渲染效果。）"));
            out.add(Message.of(++seq, "assistant", "tool",
                    "[工具调用] search(query=\"" + topic[0] + "\") → 3 条结果"));
        }
        if (sinceSeq >= 0) {
            out.removeIf(m -> m.seq() <= sinceSeq);
        }
        return out;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
