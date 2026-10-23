package com.lion.chatexporter;

import com.lion.chatexporter.repository.SnapshotRepository;
import com.lion.chatexporter.service.ExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最小端到端测试：用 mock 适配器跑一次完整导出，验证 快照→会话→消息 三层都落库。
 *
 * <p>测试数据写到 target/test-data，不污染真实的 ./data。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.data-dir=./target/test-data",
        "app.platform=mock",
        "app.concurrency=3",
        "app.request-interval-ms=0",
        "app.assets.enabled=false",   // 单测不联网：附件落盘由本地手动验证
        "logging.file.name=./target/test-data/logs/test.log",
        "app.queue.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class MockExportTest {

    @Autowired
    private ExportService exportService;

    @Autowired
    private SnapshotRepository repo;

    @Test
    void fullExportWithMockAdapter() {
        long snapshotId = exportService.runSync("mock", "单元测试快照", null);
        assertTrue(snapshotId > 0, "应创建快照");

        List<Map<String, Object>> snapshots = repo.findSnapshots();
        assertFalse(snapshots.isEmpty(), "快照列表不该为空");

        List<Map<String, Object>> convs = repo.findConversations(snapshotId);
        assertEquals(6, convs.size(), "mock 平台有 6 个会话");

        Object rowId = convs.get(0).get("id");
        List<Map<String, Object>> msgs = repo.findMessages(((Number) rowId).longValue());
        // 3 轮 × (问 + 思考 + 答 + 工具) = 12，外加最后一条带图片的消息 = 13
        assertEquals(13, msgs.size(), "每个会话 13 条消息（含 1 条图片消息）");
        assertTrue(msgs.stream().anyMatch(m -> String.valueOf(m.get("content")).contains("![示例图片]")),
                "应包含图片消息，用于验证附件链路");
        assertTrue(msgs.stream().anyMatch(m -> "thinking".equals(m.get("kind"))), "应包含思考块");
    }
}
