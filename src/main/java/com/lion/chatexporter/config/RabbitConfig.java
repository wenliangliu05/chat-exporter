package com.lion.chatexporter.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑。
 *
 * <p>只有一个队列：每个"会话"是一条消息（SessionTask）。
 * 不用 exchange 路由，是因为这里只有一种任务类型——多一层路由只会增加理解成本。
 *
 * <p>为什么队列要 durable？broker 重启后任务不丢。
 * 消息本身用 Java 序列化（SimpleMessageConverter 默认行为），零额外依赖；
 * 将来要跨语言消费再换 JSON。
 */
@Configuration
public class RabbitConfig {

    public static final String QUEUE = "chat.export.session";

    @Bean
    public Queue exportQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }
}
