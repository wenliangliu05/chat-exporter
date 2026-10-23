package com.lion.chatexporter.controller;

import com.lion.chatexporter.service.ProfileService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Profile（数据目录）与 Cookie 管理 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    public record ActivateRequest(String path) {}

    public record CookieRequest(String platform, String cookie) {}

    /** 当前 profile 全貌：绝对路径、库大小、快照数、Cookie 状态 */
    @GetMapping
    public Map<String, Object> info() {
        return profiles.info();
    }

    @GetMapping("/candidates")
    public List<String> candidates() {
        return profiles.candidates();
    }

    @PostMapping("/activate")
    public Map<String, Object> activate(@RequestBody ActivateRequest req) {
        return profiles.activate(req.path());
    }

    @PostMapping("/cookie")
    public Map<String, Object> saveCookie(@RequestBody CookieRequest req) {
        return profiles.saveCookie(req.platform(), req.cookie());
    }

    /** 本地记忆：上次的目录 / 平台 / 快照 / 会话 */
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return profiles.settingsView();
    }

    /** 更新界面状态（只写本地用户目录，不入库、不联网） */
    @PostMapping("/settings")
    public Map<String, Object> updateSettings(@RequestBody Map<String, Object> patch) {
        return profiles.updateSettings(patch);
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        return profiles.reindex();
    }

    @PostMapping("/check")
    public Map<String, Object> check() {
        return profiles.checkOnline();
    }
}
