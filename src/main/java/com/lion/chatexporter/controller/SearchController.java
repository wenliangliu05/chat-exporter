package com.lion.chatexporter.controller;

import com.lion.chatexporter.service.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 全文检索 */
@RestController
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    /**
     * @param q          关键词（≥3 字走 FTS，短词走 LIKE）
     * @param snapshotId 限定某个快照（= 某平台某次导出）
     * @param platform   限定平台（doubao / openai / mock）
     */
    @GetMapping("/api/search")
    public Map<String, Object> search(@RequestParam("q") String q,
                                      @RequestParam(name = "snapshotId", required = false) Long snapshotId,
                                      @RequestParam(name = "platform", required = false) String platform,
                                      @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return search.search(q, snapshotId, platform, limit);
    }
}
