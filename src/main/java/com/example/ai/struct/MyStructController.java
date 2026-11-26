package com.example.ai.struct;

import com.example.ai.common.MyMap;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * ★ 结构化输出（Structured Output）接口 —— 从易到难两个档位。
 * <p>
 * 核心一行：{@code .call().entity(Dto.class)} —— 模型按 DTO 字段约束输出 JSON，
 * 框架自动反序列化成强类型对象。区别于自由文本：拿到的是"对象"不是"字符串"。
 * <p>
 * 档位：
 *   ① /entity → 单个对象：一句自然语言 → UserDto（record + 嵌套 List&lt;String&gt;）
 *   ② /list   → 对象集合：一段文字 → List&lt;SkillDto&gt;（泛型必须 ParameterizedTypeReference）
 * <p>
 * 用 {@code .responseEntity(...)} 而不是 {@code .entity(...)}：对象一样，但 responseEntity
 * 额外带回完整 ChatResponse（原始文本），教学上能同时展示"模型说了什么"和"反序列化成了什么"。
 */
@RestController
@RequestMapping("/ai/struct")
@RequiredArgsConstructor
@Tag(name = "结构化输出 Structured Output", description = "让模型按 DTO 契约输出 JSON 并反序列化成对象：①/entity 单个对象 → ②/list 对象集合（泛型）。")
public class MyStructController {

    /** key = 通道名 + "ChatClient"，和 /ai/chat 共用同一批 bean */
    private final Map<String, ChatClient> chatClients;

    record StructRequest(String provider, String text) {}

    /** ① 单个对象：一句自我介绍 → UserDto */
    @PostMapping("/entity")
    @Operation(summary = "① 单个对象：自然语言 → UserDto",
            description = "输入一句自我介绍，模型按 UserDto(name/age/city/hobbies) 输出 JSON 并反序列化成对象。")
    public Map<String, Object> entity(@RequestBody StructRequest body) {
        var provider = body.provider() == null || body.provider().isBlank() ? "dashscope" : body.provider();
        var text = body.text() == null || body.text().isBlank() ? "我叫小韩，30岁，住在南京，爱好跑步和摄影" : body.text();
        var client = chatClients.get(provider + "ChatClient");
        if (client == null) return MyMap.of("provider", provider, "ok", false, "error", "通道不可用：" + provider);

        long start = System.currentTimeMillis();
        try {
            var re = client.prompt().user(text).call().responseEntity(UserDto.class);
            return MyMap.of(
                    "provider", provider, "ok", true,
                    "text", text,
                    "output", re.getEntity(),
                    "raw", re.getResponse().getResult().getOutput().getText(),
                    "elapsedMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            return MyMap.of("provider", provider, "ok", false,
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "elapsedMs", System.currentTimeMillis() - start);
        }
    }

    /** ② 对象集合：一段技术栈介绍 → List<SkillDto> */
    @PostMapping("/list")
    @Operation(summary = "② 对象集合：一段文字 → List<SkillDto>（泛型）",
            description = "输入一段介绍技术栈的文字，模型提取多个技能，返回 List<SkillDto>。")
    public Map<String, Object> list(@RequestBody StructRequest body) {
        var provider = body.provider() == null || body.provider().isBlank() ? "dashscope" : body.provider();
        var text = body.text() == null || body.text().isBlank() ? "我熟悉 Java、Spring Boot 和 MySQL" : body.text();
        var client = chatClients.get(provider + "ChatClient");
        if (client == null) return MyMap.of("provider", provider, "ok", false, "error", "通道不可用：" + provider);

        long start = System.currentTimeMillis();
        try {
            // 小模型偶尔输出不合法 JSON —— 用 system 提示强化"只输出 JSON 数组"，仍失败则框架校验重试后报错（见教学页）
            var re = client.prompt()
                    .system("你是信息提取助手。只输出一个 JSON 数组，每个元素是 {\"name\":\"技能名\",\"level\":\"熟练度\"}，禁止任何多余文字、注释或标点。")
                    .user(text)
                    .call()
                    .responseEntity(new ParameterizedTypeReference<List<SkillDto>>() {});
            return MyMap.of(
                    "provider", provider, "ok", true,
                    "text", text,
                    "output", re.getEntity(),
                    "raw", re.getResponse().getResult().getOutput().getText(),
                    "elapsedMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            return MyMap.of("provider", provider, "ok", false,
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "elapsedMs", System.currentTimeMillis() - start);
        }
    }
}
