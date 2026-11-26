package com.example.ai.learn;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习站文档的在线展示与编辑。
 * <p>
 * 背景：《教学页编写规范》原来只是一份放在站点目录里的 .md。
 * 新增教学页时照着它改，但每次改都得 ssh 上云机、进目录、编辑 —— 而站点目录
 * 不在 git 仓库里（只有一份发布源），改完容易和仓库那份对不上，
 * 哪天忘了同步就又是一版。这接口让文档在网页里直接改、直接存。
 * <p>
 * 落盘位置：站点目录下的 _docs/，和教学页同一棵树 —— 这样存完立刻能
 * curl 到、静态服务也能读，不存在"接口一份、静态一份"的分叉。
 * <p>
 * 安全（三道）：
 * <ol>
 *   <li><b>文件名白名单</b> —— 只认 DOCS 里登记的文件名，其余一律 400。
 *       绝不接受前端传路径，这是唯一的写入目标控制；拼路径再拼个 ../ 就能写到
 *       nginx 配置甚至 SSH 公钥上。</li>
 *   <li><b>不做鉴权</b> —— 鉴权交给 nginx 的 auth_request（复用 /learn_auth）。
 *       接口自己查 token 会和网关那份逻辑重复，且代理链上 cookie 透传已经够用。</li>
 *   <li><b>原子写</b> —— 先写 .tmp 再 rename。直接 Files.write 万一写到一半
 *       进程被杀 / 磁盘满，文件就剩半截，文档直接废掉。</li>
 * </ol>
 */
@RestController
@RequestMapping("/ai/learn-doc")
public class LearnDocController {

    /**
     * 可编辑文档白名单：逻辑名 → 磁盘文件名。
     * <p>
     * 只放站点目录下的纯文件名，键是 URL 里用的名字。
     * 加新文档改这里一行，前端列表也就自动多一项。
     */
    private static final Map<String, String> DOCS = new LinkedHashMap<>(Map.of(
            "spec", "教学页编写规范.md"
    ));

    /** 站点根目录。ms-ai 以 root 跑，这里直接写绝对路径最省事；
     *  用 -Dmslearn.root=xxx 可覆盖，方便本地/测试环境指到别的目录。 */
    private static final String ROOT = System.getProperty("mslearn.root", "/usr/share/nginx/html/learn/ms-demo/_docs");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Path docDir() throws IOException {
        Path d = Path.of(ROOT);
        Files.createDirectories(d);
        return d;
    }

    /** 文档清单 + 每篇的元信息，前端左侧目录直接吃这个。 */
    public record DocInfo(String name, String title, long bytes, String updated) {
    }

    @GetMapping
    public List<DocInfo> list() {
        Path d;
        try {
            d = docDir();
        } catch (IOException e) {
            return List.of();
        }
        return DOCS.entrySet().stream().map(e -> {
            Path f = d.resolve(e.getValue());
            // size 和 mtime 都可能抛 IOException（文件正被改、权限不足）。
            // 这里必须吞掉：元信息只是展示用的，拿不到就显示 0 和空时间，
            // 不能让整个列表接口因为一篇文档的 stat 失败就 500。
            long size = 0;
            String upd = "";
            try {
                if (Files.exists(f)) {
                    size = Files.size(f);
                    upd = LocalDateTime.ofInstant(Files.getLastModifiedTime(f).toInstant(),
                            java.time.ZoneId.systemDefault()).format(STAMP);
                }
            } catch (IOException ignored) {
                upd = "";
            }
            return new DocInfo(e.getKey(), e.getValue(), size, upd);
        }).toList();
    }

    /** 取一篇文档的正文。返回原始 Markdown，前端自己渲染。 */
    @GetMapping("/{name}")
    public Map<String, Object> get(@org.springframework.web.bind.annotation.PathVariable String name) throws IOException {
        String file = DOCS.get(name);
        if (file == null) return Map.of("error", "没有这篇文档: " + name);
        Path f = docDir().resolve(file);
        // 文件不存在时回落到 classpath 的种子版本，接口照样 200 ——
        // 站点目录被清空也不至于打不开页面，只是变成只读内容。
        String body;
        boolean fromSeed = false;
        if (Files.exists(f)) {
            body = Files.readString(f, StandardCharsets.UTF_8);
        } else {
            try (var in = getClass().getResourceAsStream("/docs/" + file)) {
                if (in == null) return Map.of("error", "文件不存在: " + file);
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                fromSeed = true;
            }
        }
        return Map.of("name", name, "title", file, "body", body, "seed", fromSeed);
    }

    /** 覆盖保存一篇文档。 */
    @PutMapping("/{name}")
    public Map<String, Object> save(@org.springframework.web.bind.annotation.PathVariable String name,
                                    @RequestBody Map<String, String> payload) throws IOException {
        String file = DOCS.get(name);
        if (file == null) return Map.of("error", "没有这篇文档: " + name);
        String body = payload.get("body");
        if (body == null) return Map.of("error", "缺少 body");
        /* 拒绝空正文。
         *
         * 真实踩过：用户（或脚本）在编辑框里 Ctrl+A 再按 Delete，
         * 保存就把整篇文档写成了 0 字节。后端当时只查 null 不查空，
         * 0 字节照样走完"备份→落盘"，等发现时线上已经是空的了 ——
         * 靠时间戳备份才捞回来。
         *
         * 阈值 40 字节而不是 0：正文被误删一大段（比如只剩一个标题）
         * 同样是事故，只是发生得晚一点、发现得更晚。规范文档有 14KB，
         * 40 字节的门槛不会误伤正常保存。
         */
        if (body.strip().length() < 40) {
            return Map.of("error", "正文太短（不足 40 字符），已拒绝保存。"
                    + "如果是误操作，请从站点目录 _docs/ 的时间戳备份里恢复。");
        }

        Path d = docDir();
        Path target = d.resolve(file);
        /* 存前备份，且**按时间戳留档**而不是只留一份 .bak。
         * 只留 .bak 的话，上上次的内容写完就被这次覆盖了 ——
         * 真写坏了才发现前两天那版也找不回来。
         * 攒多了自己删：只保留最近 10 份，够回退又不至于把盘塞满。 */
        if (Files.exists(target)) {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path bak = d.resolve(file + "." + stamp + ".bak");
            Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
            pruneOldBackups(d, file, 10);
        }
        Path tmp = d.resolve(file + ".tmp");
        Files.writeString(tmp, body, StandardCharsets.UTF_8);
        // rename 是原子的：要么整个新文件到位，要么还是旧内容，不会留半截
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);

        return Map.of("ok", true, "bytes", body.getBytes(StandardCharsets.UTF_8).length,
                "updated", LocalDateTime.now().format(STAMP));
    }

    /** 只保留最近 keep 份备份，文件名按时间排序（字典序 = 时间序）。 */
    private void pruneOldBackups(Path dir, String file, int keep) {
        String prefix = file + ".";
        try (var s = Files.list(dir)) {
            var baks = s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(prefix) && n.endsWith(".bak");
            }).sorted().toList();
            for (int i = 0; i < baks.size() - keep; i++) {
                Files.deleteIfExists(baks.get(i));
            }
        } catch (IOException ignored) {
            // 清理失败不影响保存本身，留着旧备份只是占点地方
        }
    }
}