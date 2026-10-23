package com.lion.chatexporter.adapter;

/**
 * 工具模型的输入：一次抓取所需的"凭据 + 限速"。
 *
 * <p>刻意不把 Cookie 塞进全局配置就完事：把它做成方法参数，
 * 这样"用哪个账号抓"是显式的，将来支持多账号只改一个地方。
 */
public record AdapterContext(String cookie, long requestIntervalMs) {
}
