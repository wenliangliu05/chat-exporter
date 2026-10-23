package com.lion.chatexporter.service;

import com.lion.chatexporter.adapter.AdapterContext;
import com.lion.chatexporter.adapter.ChatPlatformAdapter;
import com.lion.chatexporter.config.AppProperties;
import com.lion.chatexporter.config.ProfileContext;
import com.lion.chatexporter.config.RabbitConfig;
import com.lion.chatexporter.domain.Conversation;
import com.lion.chatexporter.domain.Message;
import com.lion.chatexporter.executor.ExportJob;
import com.lion.chatexporter.executor.ExportJobRegistry;
import com.lion.chatexporter.executor.SessionTask;
import com.lion.chatexporter.repository.MarkdownWriter;
import com.lion.chatexporter.repository.SnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 导出编排。
 *
 * <pre>
 * start()                        ┌── RabbitMQ ──┐
 *   ├─ 拉会话列表                 │ SessionTask  │  每个会话一条消息
 *   ├─ 建/克隆快照               └──────┬───────┘
 *   └─ 为每个会话发一条消息 ────────────┘
 *                              ↓ 消费者（concurrency 个）并发抓取
 *                       写 Markdown → 写 SQLite → 汇报进度
 *                              ↓ 最后一条完成
 *                        汇总统计（耗时 / 字数）→ 任务结束
 * </pre>
 *
 * <p>为什么用队列而不是直接开线程池？
 * 队列天然限流、可持久化、可观测（队列长度 = 剩余任务），而且消费者数量由配置决定，
 * 不会因为会话多就把对方站点打挂。
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final List<ChatPlatformAdapter> adapters;
    private final SnapshotRepository repo;
    private final MarkdownWriter markdown;
    private final ExportJobRegistry jobs;
    private final AppProperties props;
    private final Executor exportExecutor;
    private final RabbitTemplate rabbit;
    private final ProfileContext profile;
    private final ExecutorService localPool;

    /** jobId → 抓取上下文（cookie/限速）。放在内存里，避免每条消息都携带 4KB Cookie */
    private final Map<String, AdapterContext> contexts = new ConcurrentHashMap<>();
    /** 防止重复收尾（多个消费者同时减到 0 时） */
    private final Set<String> finalized = ConcurrentHashMap.newKeySet();

    public ExportService(List<ChatPlatformAdapter> adapters,
                         SnapshotRepository repo,
                         MarkdownWriter markdown,
                         ExportJobRegistry jobs,
                         AppProperties props,
                         @Qualifier("exportExecutor") Executor exportExecutor,
                         RabbitTemplate rabbit,
                         ProfileContext profile) {
        this.adapters = adapters;
        this.repo = repo;
        this.markdown = markdown;
        this.jobs = jobs;
        this.props = props;
        this.exportExecutor = exportExecutor;
        this.rabbit = rabbit;
        this.profile = profile;
        this.localPool = Executors.newFixedThreadPool(Math.max(1, props.concurrency()));
    }

    public ChatPlatformAdapter adapterFor(String platformCode) {
        String code = (platformCode == null || platformCode.isBlank()) ? props.platform() : platformCode;
        return adapters.stream()
                .filter(a -> a.platformId().equalsIgnoreCase(code))
                .findFirst()
                .orElseGet(() -> adapters.stream()
                        .filter(a -> a.platformId().equals("mock"))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("没有可用适配器")));
    }

    /** 全量/增量统一入口；baseSnapshotId 为空即全量 */
    public ExportJob start(String platform, String label, String cookie, Long baseSnapshotId) {
        ExportJob job = jobs.create(platform, label, baseSnapshotId != null);
        exportExecutor.execute(() -> prepare(job, cookie, baseSnapshotId));
        return job;
    }

    // ==================== 准备阶段 ====================

    private void prepare(ExportJob job, String rawCookie, Long baseSnapshotId) {
        try {
            AdapterContext ctx = new AdapterContext(resolveCookie(job.platform(), rawCookie), props.requestIntervalMs());
            contexts.put(job.id(), ctx);
            ChatPlatformAdapter adapter = adapterFor(job.platform());

            job.phase("拉取会话列表…");
            List<Conversation> conversations = adapter.listConversations(ctx);

            long snapId;
            if (baseSnapshotId == null) {
                snapId = repo.createSnapshot(job.platform(), "unknown", job.label(), null, "全量导出");
            } else {
                snapId = repo.createSnapshot(job.platform(), "unknown", job.label(),
                        baseSnapshotId, "增量，基于快照 #" + baseSnapshotId);
                copyBase(baseSnapshotId, snapId);
            }
            job.snapshotId(snapId);

            job.total(conversations.size());
            job.remaining().set(conversations.size());

            if (conversations.isEmpty()) {
                finalizeJob(job);
                return;
            }

            for (Conversation c : conversations) {
                long since = -1;
                if (baseSnapshotId != null) {
                    Long row = repo.findConversationRow(snapId, c.convKey());
                    if (row != null) {
                        since = repo.maxSeq(row);   // 增量锚点 = 已有最大 seq
                    }
                }
                dispatch(new SessionTask(job.id(), snapId, c.convKey(), c.title(), since));
            }
            job.phase(job.label() + "：已派发 " + conversations.size() + " 个会话" + (baseSnapshotId == null ? "" : "（增量）"));
        } catch (Exception e) {
            log.error("导出准备失败", e);
            job.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** 增量：把基准快照的会话与历史消息整段复制到新快照 */
    private void copyBase(long baseId, long newId) {
        for (Map<String, Object> row : repo.findConversations(baseId)) {
            String key = String.valueOf(row.get("conv_key"));
            String title = row.get("title") == null ? key : String.valueOf(row.get("title"));
            long oldRow = ((Number) row.get("id")).longValue();
            long newRow = repo.createConversation(newId, new Conversation(key, title), null);
            int copied = repo.copyMessages(newRow, oldRow);
            repo.updateConversation(newRow, copied, null);
        }
        log.info("增量：已复制基准快照 #{} 的历史到 #{}", baseId, newId);
    }

    private void dispatch(SessionTask task) {
        if (props.queue().enabled()) {
            rabbit.convertAndSend(RabbitConfig.QUEUE, task.toJson());   // JSON 字符串消息
        } else {
            localPool.submit(() -> handleTask(task));   // 无 broker 时的退路（测试用）
        }
    }

    // ==================== 单个会话（消费者调用） ====================

    public void handleTask(SessionTask task) {
        ExportJob job = jobs.get(task.jobId());
        AdapterContext ctx = contexts.get(task.jobId());
        if (job == null || ctx == null) {
            return;                                   // 任务已被清理（比如重启），丢弃
        }
        try {
            ChatPlatformAdapter adapter = adapterFor(job.platform());
            List<Message> messages = adapter.fetchMessages(ctx, task.convKey(), task.sinceSeq());

            Long rowId = repo.findConversationRow(task.snapshotId(), task.convKey());
            if (rowId == null) {
                Conversation c = new Conversation(task.convKey(), task.title());
                c.addMessages(messages);
                String mdPath = markdown.write(task.snapshotId(), c);
                rowId = repo.createConversation(task.snapshotId(), c, mdPath);
                repo.insertMessages(rowId, messages);
            } else {
                repo.insertMessages(rowId, messages);
                // 增量：历史在库里，重新整篇渲染（保证文件是完整的）
                String mdPath = markdown.writeFromRows(task.snapshotId(), task.convKey(), task.title(),
                        repo.findMessages(rowId));
                repo.updateConversation(rowId, repo.countMessages(rowId), mdPath);
            }
            job.done(task.title(), messages.size());
        } catch (Exception e) {
            log.warn("会话失败 {}: {}", task.convKey(), e.getMessage());
            job.fail(task.title(), e.getMessage());
        } finally {
            if (job.remaining().decrementAndGet() <= 0) {
                finalizeJob(job);
            }
        }
    }

    // ==================== 收尾 ====================

    private void finalizeJob(ExportJob job) {
        if (!finalized.add(job.id())) {
            return;                                   // 已经收过尾
        }
        try {
            Long snapId = job.snapshotId();
            if (snapId == null) {
                job.failed("快照未创建");
                return;
            }
            long[] st = repo.stats(snapId);           // {消息数, 总字数, 有效字数}
            int convs = repo.countConversations(snapId);
            long duration = job.durationMs();
            repo.updateSnapshotStats(snapId, convs, (int) st[0], duration, st[2]);
            job.finish(snapId, convs, (int) st[0], duration, st[2]);
            log.info("导出完成 snapshot={} 会话={} 消息={} 有效字数={} 耗时={}ms",
                    snapId, convs, st[0], st[2], duration);
        } catch (Exception e) {
            log.error("收尾失败", e);
            job.failed(e.getMessage());
        } finally {
            contexts.remove(job.id());
        }
    }

    /** 同步跑一次（测试/命令行用；不走 MQ） */
    public long runSync(String platform, String label, String cookie) {
        ExportJob job = jobs.create(platform, label, false);
        prepare(job, cookie, null);
        // prepare 是异步派发，这里等它跑完（本地模式）
        long deadline = System.currentTimeMillis() + 10 * 60 * 1000;
        while (job.status() == ExportJob.Status.RUNNING && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (job.status() == ExportJob.Status.FAILED) {
            throw new IllegalStateException(job.error());
        }
        return job.snapshotId();
    }

    /**
     * Cookie 解析顺序（便于脚本化运行，也不用把凭据写进命令行）：
     * <pre>
     * 1. 前端/请求体显式传入
     * 2. app.&lt;platform&gt;.cookie 配置项
     * 3. data/cookie-&lt;platform&gt;.txt   （约定式，如 cookie-doubao.txt / cookie-openai.txt）
     * 4. data/cookie.txt              （通用兜底）
     * </pre>
     * data/ 已在 .gitignore 里，凭据不会进仓库。
     */
    private String resolveCookie(String platform, String fromRequest) {
        if (fromRequest != null && !fromRequest.isBlank()) {
            return fromRequest;
        }
        if ("doubao".equalsIgnoreCase(platform) && props.doubao().cookie() != null
                && !props.doubao().cookie().isBlank()) {
            return props.doubao().cookie();
        }
        if ("openai".equalsIgnoreCase(platform) && props.openai().cookie() != null
                && !props.openai().cookie().isBlank()) {
            return props.openai().cookie();
        }
        for (String candidate : new String[]{
                profile.cookieFile(platform).toString(),
                profile.dir() + "/cookie.txt"}) {
            try {
                Path f = Path.of(candidate);
                if (Files.exists(f)) {
                    return Files.readString(f).trim();
                }
            } catch (Exception ignored) {
                // 读不到就继续找下一个
            }
        }
        return "";
    }
}
