package com.lion.chatexporter.controller;

import com.lion.chatexporter.executor.ExportJob;
import com.lion.chatexporter.executor.ExportJobRegistry;
import com.lion.chatexporter.service.ExportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 导出接口。SSE 推送进度，前端不需要轮询刷新。
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService exportService;
    private final ExportJobRegistry jobs;

    public ExportController(ExportService exportService, ExportJobRegistry jobs) {
        this.exportService = exportService;
        this.jobs = jobs;
    }

    public record ExportRequest(String platform, String label, String cookie, Long baseSnapshotId) {}

    @PostMapping("/full")
    public Map<String, Object> full(@RequestBody ExportRequest req) {
        return start(req, null);
    }

    /** 增量：基于已有快照，只抓新增消息 */
    @PostMapping("/incremental")
    public Map<String, Object> incremental(@RequestBody ExportRequest req) {
        if (req.baseSnapshotId() == null) {
            throw new IllegalArgumentException("增量导出必须指定 baseSnapshotId");
        }
        return start(req, req.baseSnapshotId());
    }

    private Map<String, Object> start(ExportRequest req, Long baseId) {
        String label = (req.label() == null || req.label().isBlank())
                ? (baseId == null ? "全量导出 " : "增量导出 ") + LocalDateTime.now().withNano(0)
                : req.label();
        ExportJob job = exportService.start(req.platform(), label, req.cookie(), baseId);
        return Map.of("jobId", job.id(), "label", label, "incremental", baseId != null);
    }

    @GetMapping("/jobs/{id}")
    public Map<String, Object> job(@PathVariable String id) {
        ExportJob job = jobs.get(id);
        return job == null ? Map.of("error", "任务不存在") : job.view();
    }

    @GetMapping(value = "/jobs/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id) {
        ExportJob job = jobs.get(id);
        if (job == null) {
            throw new IllegalArgumentException("任务不存在: " + id);
        }
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        job.attach(emitter);
        return emitter;
    }
}
