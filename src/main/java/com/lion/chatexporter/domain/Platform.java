package com.lion.chatexporter.domain;

/**
 * 业务模型第 1 层：平台。
 *
 * <p>用枚举而不是字符串：平台是封闭集合，枚举能在编译期挡住拼写错误，
 * 也方便前端/数据库做映射（code 入库，label 展示）。
 */
public enum Platform {

    DOUBAO("doubao", "豆包"),
    MOCK("mock", "模拟平台");

    private final String code;
    private final String label;

    Platform(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static Platform of(String code) {
        for (Platform p : values()) {
            if (p.code.equalsIgnoreCase(code)) {
                return p;
            }
        }
        return MOCK;
    }
}
