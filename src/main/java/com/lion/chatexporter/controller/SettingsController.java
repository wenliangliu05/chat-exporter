package com.lion.chatexporter.controller;

import com.lion.chatexporter.service.ProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 本地记忆（界面还原）。
 *
 * <p>为什么单独一个控制器：这是<b>用户级</b>设置（换 profile 也该保留），
 * 不属于 {@code /api/profile} 这个"当前数据目录"的语义，避免路径含义混淆。
 *
 * <p>数据只落到 {@code ~/.chatexporter/settings.json}：不联网、不入库、不含凭据。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final ProfileService profiles;

    public SettingsController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public Map<String, Object> get() {
        return profiles.settingsView();
    }

    /** 增量更新：{platform?, snapshotId?, conversationId?} */
    @PostMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> patch) {
        return profiles.updateSettings(patch);
    }
}
