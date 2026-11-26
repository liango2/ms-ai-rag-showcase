package com.example.ai.tools;

import com.example.ai.common.MyMap;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * ★ Function Calling（工具调用）接口 —— 从易到难一个口子。
 * <p>
 * 模型根据提问自主决定调哪个 {@code @Tool} 方法。返回里带两个关键信息：
 *   toolCalls：本次模型实际发起的工具调用（名字 + 参数 JSON）—— 这就是"模型在想什么/调了什么"的实锤；
 *   answer：模型用工具结果组织出的最终回答。
 * <p>
 * 与 /ai/chat 的唯一差别：prompt 链上多了 {@code .tools(toolService)}。ChatClient 无状态依旧成立。
 */
@RestController
@RequestMapping("/ai/tools")
@RequiredArgsConstructor
@Tag(name = "Tool 工具调用", description = "让模型调用我们自己注册的 Java 方法（@Tool）：①算加法 → ②查当前时间 → ③查多轮对话记忆条数。返回答案 + 本次实际调用的工具名/参数。")
public class ToolController {

    /** key = 通道名 + "ChatClient"（dashscopeChatClient / openaiChatClient / siliconflowChatClient），和 /ai/chat 共用同一批 bean */
    private final Map<String, ChatClient> chatClients;
    private final ToolService toolService;

    record ToolRequest(String provider, String message) {}

    @PostMapping("/ask")
    @Operation(summary = "让模型自主调用工具",
            description = "模型根据提问决定调用哪个 @Tool 方法；返回答案 + 本次实际调用的工具名/参数 JSON。")
    public Map<String, Object> ask(@RequestBody ToolRequest body) {
        var provider = body.provider() == null || body.provider().isBlank() ? "dashscope" : body.provider();
        var msg = body.message() == null || body.message().isBlank() ? "2+3 等于几？" : body.message();

        var client = chatClients.get(provider + "ChatClient");
        if (client == null) return MyMap.of(
                "provider", provider, "ok", false,
                "error", "通道不可用：" + provider);

        long start = System.currentTimeMillis();
        try {
            ChatResponse resp = client.prompt()
                    .system("你是部署在一台 2C2G 云服务器上的自学助手。当提问需要计算、查当前时间、或查多轮对话记忆条数时，调用对应工具来回答，不要凭空猜。")
                    .user(msg)
                    .tools(toolService)                     // ★ 关键：把 @Tool 方法挂到这次请求
                    .call().chatResponse();

            var answer = resp.getResult().getOutput().getText();

            // ★ 模型实际发起的工具调用：从 @Tool 方法记录里取（最终回答里不含中间 tool call，所以走 ThreadLocal）
            var toolCalls = ToolService.drainCalls();
            var toolsProvided = List.of("add", "now", "memoryCount");

            return MyMap.of(
                    "provider", provider, "ok", true,
                    "request", MyMap.of("provider", provider, "message", msg),
                    "answer", answer,
                    "toolCalls", toolCalls,
                    "toolsProvided", toolsProvided,
                    "elapsedMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            return MyMap.of(
                    "provider", provider, "ok", false,
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "elapsedMs", System.currentTimeMillis() - start);
        }
    }
}
