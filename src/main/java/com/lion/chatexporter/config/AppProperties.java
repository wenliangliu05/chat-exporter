package com.lion.chatexporter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.* 配置（record 不可变，构造器绑定）。
 *
 * <p>平台专属配置各占一个内部 record：加新平台时只加一个 record + 一个适配器，
 * 其他代码不受影响。
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String dataDir,
        String platform,
        int concurrency,
        long requestIntervalMs,
        int maxPages,
        Queue queue,
        Doubao doubao,
        OpenAi openai
) {
    public record Queue(boolean enabled) {}

    public record Doubao(
            String cookie,
            String cookieFile,
            String baseUrl,
            String extraConversationIds,
            int pageLimit,
            int batchSize
    ) {}

    public record OpenAi(
            String cookie,
            String baseUrl
    ) {}

    public String markdownDir() {
        return dataDir + "/markdown";
    }
}
