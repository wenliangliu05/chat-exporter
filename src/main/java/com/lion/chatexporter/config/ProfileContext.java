package com.lion.chatexporter.config;

import com.lion.chatexporter.repository.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteDataSource;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 「Profile」= 一个数据目录，里面装着这个账号的全部导出资产：
 *
 * <pre>
 * &lt;profile&gt;/
 *   ├── snapshot.db      SQLite（快照 + 消息 + 全文索引）
 *   ├── markdown/        导出的 Markdown
 *   ├── assets/          下载的图片/附件（可选）
 *   ├── cookie-*.txt     各平台 Cookie
 *   └── logs/
 * </pre>
 *
 * <p><b>为什么不用 Spring 的 DataSource + Hikari？</b>
 * 因为要支持"运行时切换 profile"。Spring 的 DataSource 是启动时绑定死的，
 * 换个目录就得重启。这里自己管一个 {@link SQLiteDataSource}，切换时重建即可，
 * 顺便也贴合 SQLite 单写者模型（不需要连接池）。
 *
 * <p>路径一律转成绝对路径：否则 `./data` 会跟着"从哪个目录启动"漂移，
 * 表现就是"明明导出过，打开却是空的"。
 */
@Component
public class ProfileContext {

    private static final Logger log = LoggerFactory.getLogger(ProfileContext.class);

    private final AppProperties props;

    private volatile String dir;
    private volatile JdbcTemplate jdbc;

    public ProfileContext(AppProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        activate(resolveDataDir(props.dataDir()));
    }

    /**
     * 相对路径的 {@code ./data} 到底指哪儿？—— 指"进程的当前工作目录"，不是项目目录。
     *
     * <p>这就是"IDEA 里跑起来却没有数据"的根因：IDEA 的运行配置默认
     * {@code WORKING_DIRECTORY = $MODULE_WORKING_DIR$}，而如果项目是"打开父文件夹"创建的，
     * 模块目录就是父文件夹（例如 idea-workspace），于是 {@code ./data} 落到
     * {@code idea-workspace/data} —— 一个和真库完全无关的空目录。
     *
     * <p>这里按三步定位，并打印最终选择：
     * <ol>
     *   <li>配置是绝对路径 → 直接用</li>
     *   <li>当前工作目录像项目根（有 pom.xml 或 .git）→ 用它</li>
     *   <li>否则从"代码所在位置"往上找项目根（IDEA 的 target/classes、java -jar target/xx.jar 都适用）</li>
     * </ol>
     */
    private String resolveDataDir(String configured) {
        Path given = Path.of(configured);
        if (given.isAbsolute()) {
            return given.normalize().toString();
        }
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (looksLikeProjectRoot(cwd)) {
            log.info("数据目录按「当前工作目录」解析: {}（配置 {}）", cwd.resolve(configured), configured);
            return cwd.resolve(configured).normalize().toString();
        }
        Path code = codeLocation();
        for (Path c = code; c != null && c.getNameCount() > 1; c = c.getParent()) {
            if (looksLikeProjectRoot(c)) {
                log.info("当前工作目录不像项目根（{}），改用「代码所在位置」解析: {}", cwd, c.resolve(configured));
                return c.resolve(configured).normalize().toString();
            }
        }
        log.warn("未能定位项目根，回退到当前工作目录: {}（可用 app.data-dir 指定绝对路径）", cwd);
        return cwd.resolve(configured).normalize().toString();
    }

    private boolean looksLikeProjectRoot(Path p) {
        return Files.exists(p.resolve("pom.xml")) || Files.exists(p.resolve(".git"));
    }

    private Path codeLocation() {
        try {
            var src = ProfileContext.class.getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                Path loc = Path.of(src.getLocation().toURI());
                return Files.isDirectory(loc) ? loc : loc.getParent();
            }
        } catch (Exception ignored) {
            // 拿不到就退回工作目录
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    /** 切换/初始化 profile。幂等：目录不存在会创建，库不存在会建表。 */
    public synchronized void activate(String path) {
        Path p = Path.of(path).toAbsolutePath().normalize();
        try {
            Files.createDirectories(p.resolve("markdown"));
            Files.createDirectories(p.resolve("assets"));
            Files.createDirectories(p.resolve("logs"));
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据目录: " + p, e);
        }

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + p.resolve("snapshot.db"));
        JdbcTemplate t = new JdbcTemplate(ds);
        try {
            t.queryForObject("PRAGMA journal_mode=WAL", String.class);
            t.queryForObject("PRAGMA busy_timeout=5000", Integer.class);
        } catch (Exception e) {
            log.warn("SQLite PRAGMA 设置失败（不影响功能）: {}", e.getMessage());
        }
        Schema.init(t);

        this.dir = p.toString();
        this.jdbc = t;
        log.info("当前 profile: {}（库 {}）", this.dir, dbPath());
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public String dir() {
        return dir;
    }

    public String dbPath() {
        return dir + "/snapshot.db";
    }

    public Path markdownDir() {
        return Path.of(dir, "markdown");
    }

    public Path assetDir() {
        return Path.of(dir, "assets");
    }

    public Path cookieFile(String platform) {
        return Path.of(dir, "cookie-" + platform + ".txt");
    }
}
