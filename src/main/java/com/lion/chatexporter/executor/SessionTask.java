package com.lion.chatexporter.executor;

import com.lion.chatexporter.util.Json;
import com.lion.chatexporter.util.JsonParser;
import com.lion.chatexporter.util.JsonWriter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个会话的抓取请求对象（队列消息体）。
 *
 * <p>为什么把"抓一个会话"做成消息而不是线程？
 * ① 解耦：生产端只管发，消费端按自己的能力取，天然限流；
 * ② 可恢复：broker 里排队的任务不会因为应用重启而消失；
 * ③ 可观测：队列长度就是"还剩多少没抓"。
 *
 * <p>用 JSON 而不是 Java 序列化：Spring AMQP 4 出于安全默认禁止反序列化任意类
 * （会抛 SecurityException），而 JSON 既能跨语言，也能在 RabbitMQ 管理台直接看懂。
 */
public record SessionTask(
        String jobId,
        long snapshotId,
        String convKey,
        String title,
        long sinceSeq
) {

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", jobId);
        m.put("snapshotId", snapshotId);
        m.put("convKey", convKey);
        m.put("title", title);
        m.put("sinceSeq", sinceSeq);
        return JsonWriter.write(m);
    }

    public static SessionTask fromJson(String json) {
        Map<String, Object> m = Json.map(JsonParser.parse(json));
        return new SessionTask(
                Json.str(m.get("jobId"), ""),
                Json.num(m.get("snapshotId"), 0),
                Json.str(m.get("convKey"), ""),
                Json.str(m.get("title"), ""),
                Json.num(m.get("sinceSeq"), -1));
    }
}
