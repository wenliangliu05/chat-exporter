package com.lion.chatexporter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 线程池 + HTTP 客户端。
 *
 * <p>为什么要单独定义线程池？
 * 默认的 SimpleAsyncTaskExecutor 每次新建线程、无上限，抓取几百个会话时会直接把机器打爆。
 * 这里用固定大小 + 有界队列：宁可慢一点排队，也不要瞬时把对方站点打挂（也保护自己不被风控）。
 */
@Configuration
public class AsyncConfig {

    @Bean("exportExecutor")
    @Primary
    public Executor exportExecutor(AppProperties props) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(Math.max(1, props.concurrency()));
        ex.setMaxPoolSize(Math.max(1, props.concurrency()));
        ex.setQueueCapacity(1000);
        ex.setThreadNamePrefix("export-");
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return ex;
    }
}
