package com.lion.chatexporter.executor;

import com.lion.chatexporter.config.RabbitConfig;
import com.lion.chatexporter.service.ExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 队列消费者：每条消息 = 一个会话。
 *
 * <p>并发度由 spring.rabbitmq.listener.simple.concurrency 决定（= app.concurrency）。
 * prefetch=1 让任务分配尽量均匀：谁先空谁先拿，不会有人囤一堆。
 */
@Component
public class SessionTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(SessionTaskConsumer.class);

    private final ExportService exportService;

    public SessionTaskConsumer(ExportService exportService) {
        this.exportService = exportService;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void onSessionTask(String body) {
        SessionTask task = SessionTask.fromJson(body);
        log.debug("消费会话任务 {} / {}", task.jobId(), task.convKey());
        exportService.handleTask(task);
    }
}
