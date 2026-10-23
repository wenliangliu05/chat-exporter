package com.lion.chatexporter.service;

import com.lion.chatexporter.repository.SnapshotRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 只读查询：直接返回 Map，前端拿到就能用。
 *
 * <p>为什么不上 ORM / 实体映射？数据形状和 UI 需要的形状几乎一致，
 * 多一层转换只增加噪音。等将来接向量检索时再引入专门的查询模型。
 */
@Service
public class QueryService {

    private final SnapshotRepository repo;

    public QueryService(SnapshotRepository repo) {
        this.repo = repo;
    }

    public List<Map<String, Object>> snapshots() {
        return repo.findSnapshots();
    }

    public List<Map<String, Object>> conversations(long snapshotId) {
        return repo.findConversations(snapshotId);
    }

    /**
     * 分页取消息：默认<b>取最新一页</b>（聊天窗口应该先看到最近的），
     * 向上滚动时用 {@code before} 游标继续取更早的。
     */
    public Map<String, Object> messages(long conversationRowId, Long before, int limit) {
        return repo.pageMessages(conversationRowId, before, limit);
    }
}
