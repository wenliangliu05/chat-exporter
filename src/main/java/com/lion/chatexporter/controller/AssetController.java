package com.lion.chatexporter.controller;

import com.lion.chatexporter.config.ProfileContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把已落盘的附件喂给浏览器。
 *
 * <p>安全要点：只接受 {@code 快照号/文件名} 两段，且都做白名单校验，
 * 杜绝 {@code ../} 之类的路径穿越。
 */
@RestController
public class AssetController {

    private final ProfileContext profile;

    public AssetController(ProfileContext profile) {
        this.profile = profile;
    }

    @GetMapping("/api/assets/{snapshotId}/{file}")
    public ResponseEntity<Resource> get(@PathVariable String snapshotId, @PathVariable String file) {
        if (!snapshotId.matches("\\d{1,12}") || !file.matches("[A-Za-z0-9._-]{1,80}") || file.contains("..")) {
            return ResponseEntity.badRequest().build();
        }
        Path p = profile.assetDir().resolve(snapshotId).resolve(file).normalize();
        if (!p.startsWith(profile.assetDir().normalize()) || !Files.isReadable(p)) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = MediaType.APPLICATION_OCTET_STREAM;
        try {
            String probed = Files.probeContentType(p);
            if (probed != null) {
                type = MediaType.parseMediaType(probed);
            }
        } catch (Exception ignored) {
            // 探测失败就用二进制流
        }
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(p));
    }
}
