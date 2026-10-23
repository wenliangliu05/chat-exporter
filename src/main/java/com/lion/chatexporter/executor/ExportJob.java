package com.lion.chatexporter.executor;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次导出任务的运行时状态（内存态，不落库）。
 *
 * <p>并发要点：多个消费者线程会同时改这些计数器，所以用原子类；
 * SSE 连接可能在推送过程中断开，所以用 CopyOnWriteArrayList 并在发送失败时摘掉。
 */
public class ExportJob {

    public enum Status { RUNNING, DONE, FAILED }

    private final String id;
    private final String platform;
    private final String label;
    private final boolean incremental;
    private final Instant startedAt = Instant.now();

    private volatile Status status = Status.RUNNING;
    private volatile String phase = "初始化";
    private volatile Long snapshotId;
    private volatile String error;
    private volatile Instant finishedAt;
    private volatile long durationMs;
    private volatile long effectiveChars;
    private volatile int conversationCount;
    private volatile int messageCount;

    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger remaining = new AtomicInteger();
    private final AtomicInteger failedCount = new AtomicInteger();
    private final AtomicInteger messages = new AtomicInteger();

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final List<String> recent = new CopyOnWriteArrayList<>();

    public ExportJob(String id, String platform, String label, boolean incremental) {
        this.id = id;
        this.platform = platform;
        this.label = label;
        this.incremental = incremental;
    }

    public String id() { return id; }
    public String platform() { return platform; }
    public String label() { return label; }
    public Status status() { return status; }
    public Long snapshotId() { return snapshotId; }
    public String error() { return error; }
    public AtomicInteger remaining() { return remaining; }
    public int conversationCount() { return conversationCount; }

    public void phase(String p) { this.phase = p; push(); }
    public void total(int n) { total.set(n); push(); }

    public void snapshotId(Long id) {
        this.snapshotId = id;
        push();
    }

    public void done(String title, int msgCount) {
        done.incrementAndGet();
        messages.addAndGet(msgCount);
        addRecent("✓ " + title + "（+" + msgCount + " 条）");
        push();
    }

    public void fail(String title, String reason) {
        failedCount.incrementAndGet();
        addRecent("✗ " + title + "：" + shortMsg(reason));
        push();
    }

    public void finish(long snapId, int convs, int msgs, long durationMs, long chars) {
        this.snapshotId = snapId;
        this.conversationCount = convs;
        this.messageCount = msgs;
        this.durationMs = durationMs;
        this.effectiveChars = chars;
        this.phase = "完成：" + convs + " 会话 / " + msgs + " 条 / 耗时 " + fmt(durationMs);
        this.status = Status.DONE;
        this.finishedAt = Instant.now();
        push();
        completeAll();
    }

    public void failed(String reason) {
        this.error = reason;
        this.phase = "失败：" + shortMsg(reason);
        this.status = Status.FAILED;
        this.finishedAt = Instant.now();
        push();
        completeAll();
    }

    public long durationMs() {
        return Duration.between(startedAt, Instant.now()).toMillis();
    }

    public Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("platform", platform);
        m.put("label", label);
        m.put("incremental", incremental);
        m.put("status", status.name());
        m.put("phase", phase);
        m.put("total", total.get());
        m.put("done", done.get());
        m.put("failed", failedCount.get());
        m.put("messages", messages.get());
        m.put("snapshotId", snapshotId);
        m.put("durationMs", durationMs);
        m.put("effectiveChars", effectiveChars);
        m.put("conversationCount", conversationCount);
        m.put("messageCount", messageCount);
        m.put("error", error);
        m.put("elapsedMs", durationMs());
        m.put("startedAt", startedAt.toString());
        m.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        m.put("recent", List.copyOf(recent));
        return m;
    }

    public void attach(SseEmitter emitter) {
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        send(emitter, "state", view());
        if (status != Status.RUNNING) {
            emitter.complete();
        }
    }

    private void addRecent(String line) {
        recent.add(line);
        while (recent.size() > 8) {
            recent.remove(0);
        }
    }

    private void push() {
        Map<String, Object> v = view();
        for (SseEmitter e : emitters) {
            send(e, "progress", v);
        }
    }

    private void send(SseEmitter e, String event, Object data) {
        try {
            e.send(SseEmitter.event().name(event).data(data));
        } catch (Exception ex) {
            emitters.remove(e);
        }
    }

    private void completeAll() {
        for (SseEmitter e : emitters) {
            try {
                e.complete();
            } catch (Exception ignored) {
                // 客户端已断开
            }
        }
        emitters.clear();
    }

    private String shortMsg(String s) {
        if (s == null) {
            return "未知错误";
        }
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    public static String fmt(long ms) {
        if (ms < 1000) {
            return ms + " ms";
        }
        return String.format("%.1f s", ms / 1000.0);
    }
}
