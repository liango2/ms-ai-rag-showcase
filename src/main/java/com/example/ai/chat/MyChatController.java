package com.example.ai.chat;

import com.example.ai.common.MyMap;
import com.example.ai.common.TokenUsageRecorder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
// ★ ChatResponse 在 1.1.x 里从 chat.response 移到了 chat.model（已从 jar 核对）
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;

/**
 * My* = 本工程自己写的类（命名约定见 MsAiApplication）。
 * <p>
 * 最简 Chat 接口，从易到难五个档位（knife4j 文档也按这个顺序排）：
 * <pre>
 *   1  GET  /ai/chat/providers  探路：看有哪些通道可用
 *   2  GET  /ai/chat/once       非流式最简对话：等全部生成完，一次性返回字符串
 *   3  GET  /ai/chat/stream     流式对话：字一个一个往外吐（SSE / 打字机）
 *   4  POST /ai/chat            生产化完整版：ChatClient 包装层 + system + 耗时/token/模型名
 *   5  POST /ai/chat/memory     多轮对话：ChatMemory 记忆，同一 sessionId 记得上文（无状态→有状态）
 * </pre>
 * 注意：1/2/3 档用的是 ChatModel（最底层，只会 call/stream），第 4 档换成了 ChatClient
 * （在模型外面又包了一层链式 API）——所以 4 不是"once 加料"，而是"把模型接进真实服务"的新台阶；
 * 第 5 档在 ChatClient 上再挂一个 MessageChatMemoryAdvisor（记忆顾问），把历史自动拼进/存回。
 * <p>
 * 双通道对比（dashscope vs openai，同一问题同一模型同一 Key 只换 SDK）是学习关键，
 * 但现在由【前端】调两次完成（provider 换一下就是对比），后端只做单通道，保持每一档一个干净概念。
 * <p>
 * 风格约定：能一行写完就不写三行。构造器交给 Lombok，返回值交给 MyMap。
 */
@RestController
@RequestMapping("/ai/chat")
@RequiredArgsConstructor
@Tag(name = "Chat 对话接口", description = "从易到难 5 档：①探通道 → ②非流式 → ③流式 → ④生产化(ChatClient+指标) → ⑤多轮对话(ChatMemory 记忆)。双通道对比由前端调两次完成。")
// ← Lombok 生成的构造器等价于：public MyChatController(Map<String,ChatClient> chatClients){ this.chatClients = chatClients; }
public class MyChatController {

    /**
     * Spring 会把容器内所有 ChatClient bean 收进来：key = bean 名（dashscopeChatClient / openaiChatClient，见 MyAiConfig）
     */
    private final Map<String, ChatClient> chatClients;

    /**
     * 流式接口用的模型本体：ChatClient 是 .prompt() 的包装，ChatModel 才有 .stream()（见 MyAiConfig 那张图）
     * key = 各通道 ChatModel bean 名。不再注册透传别名，直接用 {@link #chatModelBean(String)} 映射到
     * auto-config 的真实 bean 名（dashScopeChatModel / openAiChatModel 带大写）。
     */
    private final Map<String, ChatModel> chatModels;

    /** Token 记账：把每次非流式调用的 usage 写进 token_usage 表（查询见 /ai/token/*） */
    private final TokenUsageRecorder tokenRecorder;

    /** provider 参数 → 对应 ChatModel bean 名。dashscope/openai 走 auto-config 方法名（带大写），siliconflow 是自注册小写 */
    private static String chatModelBean(String provider) {
        return switch (provider) {
            case "dashscope"  -> "dashScopeChatModel";   // Spring AI Alibaba 自动装配的方法名，S 大写
            case "openai"     -> "openAiChatModel";      // spring-ai-openai 自动装配的方法名，A 大写
            default           -> provider + "ChatModel";  // 我们自己注册的，名即 小写通道名 + ChatModel
        };
    }

    /**
     * 第 5 档的记忆（内存版，重启即丢）：见 MyAiConfig#chatMemory()。按 conversationId 存历史，只留最近 10 条。
     */
    @Qualifier("chatMemory")
    private final ChatMemory chatMemory;

    /**
     * 第 5+ 档的记忆（PG 落库版，重启不丢）：见 MyAiConfig#chatMemoryPg()。与内存版同构，只是「存哪」从内存换到 PostgreSQL。
     */
    @Qualifier("chatMemoryPg")
    private final ChatMemory chatMemoryPg;

    /**
     * 兜底用的模型名：某些 SDK 不回传 model 字段时用配置值补上
     */
    @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}")
    private String dashscopeModel;

    @Value("${spring.ai.openai.chat.options.model:qwen-plus}")
    private String openaiModel;

    /* =====================================================================================
     * 档位 1 · 探路（基础）
     * ===================================================================================== */

    /**
     * 可用通道列表：页面加载时先探一下，没有 key 的通道会提示而不是直接报错。
     */
    @GetMapping("/providers")
    @Operation(summary = "1 · 看有哪些通道", description = "探路。返回当前可用通道（dashscope/openai）。没配 key 的通道会在这里暴露出来，而不是报错。")
    public Map<String, Object> providers() {
        var list = chatClients.keySet().stream().map(b -> MyMap.of(
                "provider",
                shortName(b),
                "bean",
                b
                                                                  )).toList();
        return MyMap.of(
                "count",
                list.size(),
                "providers",
                list
                       );
    }

    /* =====================================================================================
     * 档位 2 · 非流式最简对话（先学会「调通一个」）
     * ===================================================================================== */

    /**
     * 非流式对照版：和下面 stream() 唯一差别是 .call() vs .stream()。
     * .call()   → 一个 ChatResponse，等全部生成完一次性返回（本方法，返回字符串）
     * .stream() → Flux&lt;ChatResponse&gt;，一个字一个字吐（见档位 3）
     * 参数、取文本的写法完全一样，方便对比「流式/非流式」只是切了个方法。
     * <p>
     * 用法：GET /ai/chat/once?message=你好&provider=dashscope
     */
    @GetMapping("/once")
    @Operation(summary = "2 · 非流式对话（最简，返回字符串）",
            description = "等模型全部生成完一次性返回。和 /stream 唯一差别是 .call() vs .stream()。")
    public String once(@RequestParam(value = "message", defaultValue = "你是谁？") String message,
                       @RequestParam(value = "provider", defaultValue = "dashscope") String provider) {
        return Optional.ofNullable(chatModels.get(chatModelBean(provider)))
                       .map(model -> model.call(new Prompt(new UserMessage(message)))
                                          .getResult()
                                          .getOutput()
                                          .getText())
                       .orElseGet(() -> "通道不可用：" + provider + "（可能没配 DASHSCOPE_KEY，或该 starter 未生效）");
    }

    /* =====================================================================================
     * 档位 3 · 流式对话（在 once 基础上把 .call() 换成 .stream()）
     * ===================================================================================== */

    /**
     * 流式响应（SSE）：模型一个字一个字往外吐，前端能做打字机效果。
     * 对应学习里 chatModel.stream(prompt) 的写法：
     * Flux&lt;ChatResponse&gt; → map 每帧取文本 → Flux&lt;String&gt;
     * <p>
     * 用法：GET /ai/chat/stream?message=你好&provider=dashscope
     * provider 缺省走 dashscope；通道不可用吐一行错误文本（不中断连接）。
     * 注意区分：ChatClient 走 .prompt().call()，ChatModel 才有 .stream()——这里用的是后者。
     */
    @GetMapping("/stream")
    @Operation(summary = "3 · 流式对话（SSE，打字机效果）",
            description = "模型一字一句往外吐，返回 Flux<String>。前端能做打字机效果。")
    public Flux<String> stream(@RequestParam(value = "message", defaultValue = "你是谁？") String message,
                               @RequestParam(value = "provider", defaultValue = "dashscope") String provider) {
        // 取通道模型；无则吐一行错误（Optional 流式兜底，与 ask() 一个套路）
        return Optional.ofNullable(chatModels.get(chatModelBean(provider)))
                       .map(model -> model.stream(new Prompt(new UserMessage(message)))
                                          .map(r -> r.getResult()
                                                     .getOutput()
                                                     .getText()))
                       .orElseGet(() -> Flux.just("通道不可用：" + provider + "（可能没配 DASHSCOPE_KEY，或该 starter 未生效）"));
    }

    /* =====================================================================================
     * 档位 4 · 生产化完整版（ChatClient + system + 指标）—— 功能最全
     * ===================================================================================== */

    @PostMapping
    @Operation(summary = "4 · 生产化完整版（ChatClient + 指标）",
            description = "单通道：ChatClient 包装层 + system 可选 + 返回耗时/token/模型名。双通道对比由前端调两次完成（provider 换 dashscope/openai）。")
    public Map<String, Object> chat(@RequestBody ChatRequest body) {
        // 参数校验
        var provider = body.provider() == null || body.provider().isBlank() ? "dashscope" : body.provider();
        if (body.message() == null || body.message().isBlank()) return MyMap.of(
                "error",
                "message 不能为空"
                                                                               );


        // [1] 取 ChatClient；无则通道不可用（缺 key 或 starter 未生效）→ 流式 orElseGet 兜底
        var client = chatClients.get(provider + "ChatClient");
        if (client == null) return MyMap.of(
                "provider",
                provider,
                "ok",
                false,
                "error",
                "通道不可用：" + provider + "（可能没配 DASHSCOPE_KEY，或该 starter 未生效）"
        );

        // 单通道 + 指标：返回这一通道的 答案/耗时/token/模型名（一个干净结果，无 both 分支）
        long start = System.currentTimeMillis();
        try {
            // [2][3] 组装 prompt + 调用：system 非空才拼，Optional.filter 吃掉「判空 + 分支」
            var promptSpec = client.prompt().user(body.message());
            if (body.system() != null && !body.system().isBlank()) {
                promptSpec = promptSpec.system(body.system());
            }
            ChatResponse response = promptSpec.call().chatResponse();

            // [4~6] 答案/模型名/耗时 + token：元数据全走 Optional，取不到就兜底或跳过
            var meta = Optional.ofNullable(response.getMetadata());
            // 使用 HashMap 包装，防止 MyMap.of 返回不可变 Map 导致 putAll 抛出 UnsupportedOperationException
            Map<String, Object> out = new java.util.HashMap<>(MyMap.of(
                    "provider",
                    provider,
                    "ok",
                    true,
                    "answer",
                    response.getResult().getOutput().getText(),
                    "model",
                    meta.map(m -> m.getModel()).filter(m -> !m.isBlank()).orElseGet(() -> configuredModel(provider)),
                    "elapsedMs",
                    System.currentTimeMillis() - start
                                                                      ));
            meta.map(m -> m.getUsage()).ifPresent(u -> out.putAll(MyMap.of(
                    "promptTokens",
                    u.getPromptTokens(),          // 输入消耗：提示词(含system、历史)的 token 数
                    "completionTokens",
                    u.getCompletionTokens(),  // 输出消耗：模型生成回答的 token 数
                    "totalTokens",
                    u.getTotalTokens()
                                                                          )));          // 总消耗 = promptTokens + completionTokens（计费依据）
            // ★ Token 记账：把本次 usage 落库（endpoint=chat），查询见 /ai/token/*（记账页/查账接口）
            meta.ifPresent(m -> tokenRecorder.record(
                    "chat", provider, String.valueOf(out.get("model")),
                    m.getUsage(), System.currentTimeMillis() - start));
            return out;
        } catch (Exception e) {
            return MyMap.of(
                    "provider",
                    provider,
                    "ok",
                    false,
                    "elapsedMs",
                    System.currentTimeMillis() - start,
                    "error",
                    e.getClass().getSimpleName() + ": " + e.getMessage()
                           );
        }
    }

    /* =====================================================================================
     * 档位 5 · 多轮对话（ChatMemory 记忆）—— 无状态 → 有状态
     * ===================================================================================== */

    /**
     * 带记忆的多轮对话：同一 sessionId 下，模型记得上文。
     * <p>
     * 原理（MessageChatMemoryAdvisor 替我们做这两件事）：
     *   before（发问前）：按 conversationId 从 ChatMemory 取出历史，拼进本次 prompt → 模型「看见上文」；
     *   after（回答后）：把本轮 user+assistant 两条消息存回 ChatMemory → 下次还能接着聊。
     * <p>
     * 为什么不直接用第 4 档的 chatClients：其实可以直接用！
     * ChatClient.prompt() 是请求级一次性链，.advisors() 只对本次生效，不会写回 bean。
     * 所以统一走 chatClients（现成 bean）+ per-request 注入记忆顾问，和 /ai/rag/ask 的 advisor 模式对称，
     * 不污染第 4 档 /chat 的无状态对话（它们各自 .prompt() 独立）。
     */
    @PostMapping("/memory")
    @Operation(summary = "5 · 多轮对话（ChatMemory 记忆 · 内存版）",
            description = "同一 sessionId 记得上文。记忆存内存，重启即丢。官方 MessageChatMemoryAdvisor 自动取/存历史（滑动窗口最近 10 条）。返回答案 + 当前记忆条数 history。")
    public Map<String, Object> chatMemory(@RequestBody ChatMemoryRequest body) {
        return chatMemoryBase(body, chatMemory);
    }

    @PostMapping("/memory-pg")
    @Operation(summary = "5+ · 多轮对话（ChatMemory 记忆 · PG 落库版，重启不丢）",
            description = "与 /memory 完全同构，只是记忆底座换成 PostgreSQL（chat_memory 表）：重启后同一 sessionId 仍记得上文。返回答案 + 当前记忆条数 history。")
    public Map<String, Object> chatMemoryPg(@RequestBody ChatMemoryRequest body) {
        return chatMemoryBase(body, chatMemoryPg);
    }

    /** 两个多轮对话接口共用的实现：唯一差别是传入的记忆底座（内存版 chatMemory / PG 版 chatMemoryPg）。 */
    private Map<String, Object> chatMemoryBase(@RequestBody ChatMemoryRequest body, ChatMemory mem) {
        // 参数校验
        var provider = body.provider() == null || body.provider().isBlank() ? "dashscope" : body.provider();
        var cid = body.sessionId() == null || body.sessionId().isBlank() ? "default" : body.sessionId();
        if (body.message() == null || body.message().isBlank()) return MyMap.of(
                "error",
                "message 不能为空"
                                                                               );
        // 统一走现成 ChatClient bean（和 /ai/rag/ask、第 4 档 /chat 一致），per-request 注入记忆顾问
        var client = chatClients.get(provider + "ChatClient");
        if (client == null) return MyMap.of(
                "provider",
                provider,
                "ok",
                false,
                "error",
                "通道不可用：" + provider
                                           );

        long start = System.currentTimeMillis();
        try {
            var answer = client.prompt()
                               .user(body.message())
                               // 本次请求临时挂记忆顾问：先设会话 id param，再挂 advisor（before 取历史回填、after 存回）
                               .advisors(a -> a.param("chat_memory_conversation_id", cid))
                               .advisors(MessageChatMemoryAdvisor.builder(mem).build())
                               .call()
                               .content();
            return MyMap.of(
                    "provider",
                    provider,
                    "ok",
                    true,
                    "answer",
                    answer,
                    "sessionId",
                    cid,
                    "history",
                    mem.get(cid).size(),       // 这个会话目前记住了几条（含本轮）
                    "elapsedMs",
                    System.currentTimeMillis() - start
                            );
        } catch (Exception e) {
            return MyMap.of(
                    "provider",
                    provider,
                    "ok",
                    false,
                    "elapsedMs",
                    System.currentTimeMillis() - start,
                    "error",
                    e.getClass().getSimpleName() + ": " + e.getMessage()
                           );
        }
    }

    /* =====================================================================================
     * 私有工具方法（不对外暴露）
     * ===================================================================================== */

    /**
     * 把 bean 名 dashscopeChatClient → 通道名 dashscope
     */
    private static String shortName(String beanName) {
        return beanName.replace(
                "ChatClient",
                ""
                               );
    }

    /**
     * SDK 没回传模型名时用配置值兜底（DashScope 原生通道实测就是空的）
     */
    private String configuredModel(String provider) {
        return "dashscope".equalsIgnoreCase(provider) ? dashscopeModel : openaiModel;
    }

    /**
     * 请求体
     */
    public record ChatRequest(String provider, String message, String system) {
    }

    /**
     * 第 5 档请求体：多了一个 sessionId（会话标识，同一会话才共享记忆）
     */
    public record ChatMemoryRequest(String provider, String sessionId, String message) {
    }
}
