package com.lion.chatexporter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 入口。
 *
 * <p>为什么排除 DataSource 自动配置？
 * 本项目要支持"运行时切换 profile（数据目录）"，而 Spring 的 DataSource 是启动时绑定死的。
 * 所以连接由 {@code ProfileContext} 自己管（SQLite 单文件库，也不需要连接池）。
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@ConfigurationPropertiesScan
public class ChatExporterApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatExporterApplication.class, args);
    }
}
