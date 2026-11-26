package com.example.ai.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 学习门户「我的笔记」自定义链接的轻量 JSON 存储。
 * <p>
 * 背景：门户 #/ 首页的学习文章卡片要能挂用户自己记录的外部笔记链接（飞书/其他），
 * 且要跨设备同步。这里用一个极简接口：GET 读、PUT 整体覆盖保存，落盘一个 JSON 文件。
 * <p>
 * 数据结构（Map 键 = 章节 id，如 ch01/ch02…，值 = 该章自定义链接数组）：
 * <pre>
 * {
 *   "ch01": [ {"name":"飞书笔记","url":"https://...","note":"补充" }, ... ],
 *   "ch02": []
 * }
 * </pre>
 * 落盘位置：{工作目录}/data/learn-links.json（生产在 /root/ms-ai/data/）。
 * <p>
 * 安全：无鉴权、可写任意 JSON 文本 —— 仅适用于单用户个人学习站；若暴露公网请加登录校验。
 */
@RestController
@RequestMapping("/ai/learn-links")
public class LearnLinksController {

    /** 每个链接的字段：名称、URL、备注 */
    public record Link(String name, String url, String note) {
    }

    /** 整份数据：章节 id → 链接列表 */
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;

    public LearnLinksController() {
        // 工作目录/data/learn-links.json；目录不存在则创建
        this.file = Path.of(System.getProperty("user.dir"), "data", "learn-links.json");
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据目录: " + file.getParent(), e);
        }
    }

    @GetMapping
    public Map<String, java.util.List<Link>> list() {
        return read();
    }

    @PutMapping
    public Map<String, java.util.List<Link>> save(@RequestBody Map<String, java.util.List<Link>> data) {
        write(data);
        return data;
    }

    private Map<String, java.util.List<Link>> read() {
        // 工作目录数据文件不存在时，用 classpath 默认种子兜底（新环境/重装服务器也能恢复首页链接）
        if (!Files.exists(file)) return seedFromClasspath();
        try {
            return mapper.readValue(file.toFile(),
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, java.util.List<Link>>>() {});
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    /** 从 jar 内置的 learn-links-default.json 读默认链接，并落盘一次（之后 GET 都走文件）。 */
    private Map<String, java.util.List<Link>> seedFromClasspath() {
        try (var in = getClass().getResourceAsStream("/learn-links-default.json")) {
            if (in == null) return new LinkedHashMap<>();
            var data = mapper.readValue(in,
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, java.util.List<Link>>>() {});
            write(data);
            return data;
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    private void write(Map<String, java.util.List<Link>> data) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (IOException e) {
            throw new IllegalStateException("保存学习链接失败: " + file, e);
        }
    }
}
