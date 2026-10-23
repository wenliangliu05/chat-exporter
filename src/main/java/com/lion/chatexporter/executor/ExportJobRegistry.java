package com.lion.chatexporter.executor;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务注册表：内存里保存"正在跑/刚跑完"的任务。
 *
 * <p>不持久化：任务只是进度的载体，重启后重新发起即可；真正的数据在 snapshot.db 里。
 */
@Component
public class ExportJobRegistry {

    private final Map<String, ExportJob> jobs = new ConcurrentHashMap<>();

    public ExportJob create(String platform, String label, boolean incremental) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        ExportJob job = new ExportJob(id, platform, label, incremental);
        jobs.put(id, job);
        return job;
    }

    public ExportJob get(String id) {
        return jobs.get(id);
    }
}
