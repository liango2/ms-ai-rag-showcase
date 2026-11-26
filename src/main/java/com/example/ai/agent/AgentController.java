package com.example.ai.agent;

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
 * ★ Agent（智能体）接口 —— 从易到难两个口子。
 * <p>
 * 与 /ai/tools/ask 的唯一差别，一句话：
 *   ask = 「一个问题、一个工具、一问一答」（Function Calling）；
 *   Agent = 「一次提问，模型面对一篮子工具<b>自主决定调哪个、调几个、怎么串</b>，连续调用后汇总回答」。
 * <p>
 * 两个接口都挂同一个 {@link AgentTools}（4 个 @Tool），靠系统提示引导模型只挑相关的用——
 * 返回里的 {@code toolCalls} 就是「模型实际挑了哪几个」的实锤，对比 {@code toolsProvided}（给了哪几个）即可看出自主性。
 */
@RestController
@RequestMapping("/ai/agent")
@RequiredArgsConstructor
@Tag(name = "Agent 智能体", description = "模型自主编排工具（多工具自主调用 + 汇总）：①旅行规划（查时间+天气）→ ②订单售后决策（查订单+查政策+综合判断）。返回答案 + 本次实际调用的工具名/参数。")
public class AgentController {

    /** key = 通道名 + "ChatClient"（dashscopeChatClient / openaiChatClient / siliconflowChatClient），和 /ai/chat 共用同一批 bean */
    private final Map<String, ChatClient> chatClients;
    private final AgentTools agentTools;

    record AgentRequest(String provider, String message) {}

    /** 公共执行：拼 prompt → 挂工具篮 → 让模型自主编排 → 取最终回答 + 实际工具调用。 */
    private Map<String, Object> runAgent(String provider, String message,
                                         String system, String defaultMsg, List<String> toolsProvided) {
        var p = provider == null || provider.isBlank() ? "dashscope" : provider;
        var msg = message == null || message.isBlank() ? defaultMsg : message;

        var client = chatClients.get(p + "ChatClient");
        if (client == null) return MyMap.of(
                "provider", p, "ok", false,
                "error", "通道不可用：" + p);

        long start = System.currentTimeMillis();
        try {
            ChatResponse resp = client.prompt()
                    .system(system)
                    .user(msg)
                    .tools(agentTools)                      // ★ 关键：把 4 个 @Tool 一起挂上，模型自己挑
                    .call().chatResponse();

            var answer = resp.getResult().getOutput().getText();

            // ★ 模型实际发起的工具调用：从 @Tool 方法记录里取（最终回答不含中间 tool call，所以走 ThreadLocal）
            var toolCalls = AgentTools.drainCalls();

            return MyMap.of(
                    "provider", p, "ok", true,
                    "request", MyMap.of("provider", p, "message", msg),
                    "answer", answer,
                    "toolCalls", toolCalls,
                    "toolsProvided", toolsProvided,
                    "elapsedMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            return MyMap.of(
                    "provider", p, "ok", false,
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "elapsedMs", System.currentTimeMillis() - start);
        }
    }

    @PostMapping("/travel")
    @Operation(summary = "① 旅行规划 Agent：模型自主查目的地时间+天气并汇总",
            description = "模型面对 timeInCity/weatherInCity 两个工具，自主决定要不要调、调哪个、怎么串，连调后汇总成『现在几点/天气/穿什么』。")
    public Map<String, Object> travel(@RequestBody AgentRequest body) {
        return runAgent(body.provider(), body.message(),
                "你是智能体。回答目的地相关问题（几点、天气、穿什么、时差）时，自主调用 timeInCity（查当前时间）和 weatherInCity（查天气），把结果汇总成一段旅行建议。不要凭空猜时间和天气。",
                "我下周去伦敦，现在那边几点、天气怎么样、穿什么合适？",
                List.of("timeInCity", "weatherInCity", "findOrder", "returnPolicy"));
    }

    @PostMapping("/refund")
    @Operation(summary = "② 订单售后决策 Agent：模型自主查订单+政策并综合判断",
            description = "模型面对 findOrder/returnPolicy 两个工具，先查订单再查政策，综合判断『能不能退/怎么退』；订单号缺失时会反问用户。")
    public Map<String, Object> refund(@RequestBody AgentRequest body) {
        return runAgent(body.provider(), body.message(),
                "你是售后客服智能体。用户要退某个订单时，先调用 findOrder 查订单，再调用 returnPolicy 查政策，最后综合两者给结论（能不能退、退多少、要不要运费）。若用户没给订单号，先反问索要；若查不到订单，如实说明。",
                "订单 A002 能不能退？",
                List.of("timeInCity", "weatherInCity", "findOrder", "returnPolicy"));
    }
}
