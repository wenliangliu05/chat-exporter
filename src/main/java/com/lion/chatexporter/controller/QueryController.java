package com.lion.chatexporter.controller;

import com.lion.chatexporter.service.QueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 只读查询接口：快照 → 会话 → 消息（分页） */
@RestController
@RequestMapping("/api")
public class QueryController {

    private final QueryService query;

    public QueryController(QueryService query) {
        this.query = query;
    }

    @GetMapping("/snapshots")
    public List<Map<String, Object>> snapshots() {
        return query.snapshots();
    }

    @GetMapping("/snapshots/{snapshotId}/conversations")
    public List<Map<String, Object>> conversations(@PathVariable long snapshotId) {
        return query.conversations(snapshotId);
    }

    /**
     * 消息分页。
     *
     * @param before 游标（上一页最旧那条的 id）；省略 = 取最新一页
     * @param limit  每页条数，默认 40
     */
    @GetMapping("/conversations/{rowId}/messages")
    public Map<String, Object> messages(@PathVariable long rowId,
                                        @RequestParam(name = "before", required = false) Long before,
                                        @RequestParam(name = "limit", defaultValue = "40") int limit) {
        return query.messages(rowId, before, limit);
    }
}
