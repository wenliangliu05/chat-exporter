package com.lion.chatexporter.config;

import com.lion.chatexporter.util.Json;
import com.lion.chatexporter.util.JsonParser;
import com.lion.chatexporter.util.JsonWriter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地「记忆」：记住上次用的数据目录、平台、快照、会话。
 *
 * <p><b>为什么放在用户目录而不是项目里</b>：
 * 这份记忆要先于"数据目录"存在 —— 否则启动时不知道该打开哪个 profile，
 * 就退化成"每次都要重新选目录"。所以它落在
 * {@code <用户主目录>/.chatexporter/settings.json}，<b>在仓库之外</b>，
 * 天生不可能被 git 提交，也不可能随项目上传。
 *
 * <p><b>存什么 / 不存什么</b>：
 * 只存"界面还原"需要的少量标识（路径、平台名、自增 id），
 * <b>不存任何 Cookie、Token、消息正文</b> —— 凭据永远只在
 * {@code <profile>/cookie-*.txt} 里，且同样在 gitignore 内。
 *
 * <p>写失败不影响功能：记忆是"锦上添花"，不能因为磁盘只读就让应用起不来。
 */
@Component
public class UserSettings {

    private static final Logger log = LoggerFactory.getLogger(UserSettings.class);

    /** 白名单：只允许这几个键，避免把任意字段写进记忆文件 */
    private static final String[] ALLOWED = {
            "lastProfile", "lastPlatform", "lastSnapshotId", "lastConversationId"
    };

    private final Path file = Path.of(System.getProperty("user.home"), ".chatexporter", "settings.json");
    private final Map<String, Object> data = new LinkedHashMap<>();

    @PostConstruct
    public void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Object parsed = JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
            Map<String, Object> m = Json.map(parsed);
            for (String k : ALLOWED) {
                if (m.containsKey(k)) {
                    data.put(k, m.get(k));
                }
            }
            log.info("已载入本地记忆: {}（上次目录 {}）", file, data.get("lastProfile"));
        } catch (Exception e) {
            log.warn("本地记忆读取失败（忽略，不影响使用）: {}", e.getMessage());
        }
    }

    public String lastProfile() {
        Object v = data.get("lastProfile");
        return v == null ? null : String.valueOf(v);
    }

    public synchronized void rememberProfile(String profileDir) {
        if (profileDir == null || profileDir.isBlank()) {
            return;
        }
        data.put("lastProfile", profileDir);
        save();
    }

    /** 界面状态：平台 / 快照 / 会话，换设备或重开浏览器后能接着看 */
    public synchronized void rememberUi(String platform, Long snapshotId, Long conversationId) {
        if (platform != null && !platform.isBlank()) {
            data.put("lastPlatform", platform);
        }
        if (snapshotId != null) {
            data.put("lastSnapshotId", snapshotId);
        }
        if (conversationId != null) {
            data.put("lastConversationId", conversationId);
        }
        save();
    }

    public Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : ALLOWED) {
            m.put(k, data.get(k));
        }
        m.put("settingsFile", file.toString());
        return m;
    }

    public String filePath() {
        return file.toString();
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JsonWriter.write(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("本地记忆写入失败（忽略）: {}", e.getMessage());
        }
    }
}
