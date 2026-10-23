package com.lion.chatexporter.service;

import com.lion.chatexporter.adapter.AdapterContext;
import com.lion.chatexporter.config.AppProperties;
import com.lion.chatexporter.config.ProfileContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图片 / 附件落盘。
 *
 * <p><b>为什么必须落盘</b>：平台给的图片地址是**带签名的临时 CDN 链接**，
 * 几小时到几天就失效。只存链接 = 导出的 Markdown 过几天全是死图。
 * 所以导出时就把二进制抓下来，正文里改成本地路径。
 *
 * <p><b>改写规则</b>：只处理两种明确语义的写法，避免把正文里随口提到的
 * 普通网址也下载一遍：
 * <ul>
 *   <li>{@code ![alt](http...)} —— 图片</li>
 *   <li>{@code [附件](http...)} —— 适配器标记的附件</li>
 * </ul>
 *
 * <p><b>失败策略</b>：超时 / 404 / 超过体积上限 / 扩展名不在白名单 → 保留原链接并记日志，
 * 绝不让「一个附件下载失败」拖垮整次导出。
 */
@Service
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\((https?://[^)\\s]+)\\)");
    private static final Pattern ATTACH = Pattern.compile("\\[附件\\]\\((https?://[^)\\s]+)\\)");

    /** 允许落盘的扩展名（其余一律不下载，防止把可执行文件拉进项目） */
    private static final Set<String> ALLOWED = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg",
            "pdf", "txt", "md", "csv", "json", "zip", "docx", "xlsx", "pptx", "mp3", "wav", "mp4");

    private final ProfileContext profile;
    private final AppProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public AssetService(ProfileContext profile, AppProperties props) {
        this.profile = profile;
        this.props = props;
    }

    public boolean enabled() {
        AppProperties.Assets a = props.assets();
        return a != null && a.enabled();
    }

    /**
     * 把正文里的远程附件改写成 {@code /api/assets/<snapshotId>/<file>}。
     * 下载失败的保持原样。
     */
    public String localize(long snapshotId, String content, AdapterContext ctx) {
        if (!enabled() || content == null || content.indexOf("](") < 0) {
            return content;
        }
        String out = rewriteImages(content, snapshotId, ctx);
        return rewriteAttachments(out, snapshotId, ctx);
    }

    private String rewriteImages(String content, long snapshotId, AdapterContext ctx) {
        Matcher m = IMAGE.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String alt = m.group(1);
            String url = m.group(2);
            String local = download(snapshotId, url, ctx);
            String repl = local == null ? m.group(0) : "![" + alt + "](" + local + ")";
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String rewriteAttachments(String content, long snapshotId, AdapterContext ctx) {
        Matcher m = ATTACH.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String url = m.group(1);
            String local = download(snapshotId, url, ctx);
            String repl = local == null ? m.group(0) : "[附件](" + local + ")";
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 下载并落盘；成功返回本地 URL，失败返回 null */
    private String download(long snapshotId, String url, AdapterContext ctx) {
        AppProperties.Assets cfg = props.assets();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(cfg.timeoutMs()))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ChatExporter")
                    .GET();
            if (ctx != null && ctx.cookie() != null && !ctx.cookie().isBlank()) {
                b.header("Cookie", ctx.cookie());
            }
            HttpResponse<byte[]> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                log.debug("附件下载失败 HTTP {}: {}", resp.statusCode(), url);
                return null;
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0) {
                return null;
            }
            if (body.length > cfg.maxBytes()) {
                log.warn("附件超过上限（{} > {} 字节），跳过: {}", body.length, cfg.maxBytes(), url);
                return null;
            }
            String ext = extension(url, resp.headers().firstValue("Content-Type").orElse(""));
            if (!ALLOWED.contains(ext)) {
                log.debug("附件类型不在白名单（{}），跳过: {}", ext, url);
                return null;
            }
            String name = sha1(url).substring(0, 16) + "." + ext;
            Path dir = profile.assetDir().resolve(String.valueOf(snapshotId));
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), body);
            return "/api/assets/" + snapshotId + "/" + name;
        } catch (Exception e) {
            log.debug("附件下载异常（保留原链接）: {} - {}", url, e.getMessage());
            return null;
        }
    }

    private String extension(String url, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("png")) return "png";
        if (ct.contains("jpeg") || ct.contains("jpg")) return "jpg";
        if (ct.contains("gif")) return "gif";
        if (ct.contains("webp")) return "webp";
        if (ct.contains("svg")) return "svg";
        if (ct.contains("pdf")) return "pdf";
        if (ct.contains("json")) return "json";
        if (ct.contains("zip")) return "zip";
        if (ct.startsWith("text/")) return "txt";

        String path = url.split("\\?")[0];
        int dot = path.lastIndexOf('.');
        if (dot > 0 && dot > path.length() - 6) {
            String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (ext.matches("[a-z0-9]{1,5}")) {
                return ext;
            }
        }
        return "bin";
    }

    private String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
