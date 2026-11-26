package com.example.ai.stream;

import com.example.ai.common.MyMap;
import com.example.ai.common.TokenUsageRecorder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ★ 流式输出（Streaming）接口 —— 从易到难三个档位，全程后端视角。
 * <p>
 * 核心一行：{@code client.prompt().user(msg).stream().content()} → 返回 {@code Flux<String>}。
 * 区别阻塞式 .call()：阻塞是一次性等全部算完返回一个字符串；流式是"边算边吐"，
 * 每个 token 是 Flux 里的一个元素，一个字一个字推给前端（打字机效果）。
 * <p>
 * 档位：
 *   ① /chat       → 裸流 Flux&lt;String&gt;：最小实现，每帧一个字/词
 *   ② /sse        → Flux&lt;ServerSentEvent&lt;String&gt;&gt;：显式事件帧（event 名 + data + 结束帧 [DONE]），看清 SSE 帧结构
 *   ③ /chat-meta  → 生产版：meta 帧（通道/开始时间）→ delta 帧（逐段输出）→ done 帧（耗时/帧数统计）；
 *                    顺带演示服务端"断开怎么办"（doOnCancel 停播，不白算）
 * <p>
 * 涉及的类与所在依赖（一条线，教学页对照）：
 *   ChatClient          org.springframework.ai.chat.client.ChatClient   → spring-ai-client-chat（spring-ai-bom 1.1.8 管理）
 *     .prompt().stream()  → ChatClient.StreamResponseSpec（流式响应句柄）→ 同上
 *       .content()        → reactor.core.publisher.Flux<String>         → io.projectreactor:reactor-core（异步响应式流，水龙头）
 *   ServerSentEvent     org.springframework.http.codec.ServerSentEvent → spring-web（org.springframework:spring-web，随 starter-web 来）
 *   MediaType.TEXT_EVENT_STREAM_VALUE  → spring-web（响应类型 text/event-stream）
 *   前端（只懂概念，交给 AI）：fetch + ReadableStream 逐帧读，或 EventSource（仅 GET）。
 */
@Slf4j
@RestController
@RequestMapping("/ai/stream")
@RequiredArgsConstructor
@Tag(name = "流式输出 Streaming", description = "边算边吐，Flux<String> 一个字一个字推给前端：①/chat 裸流 → ②/sse 显式事件帧 → ③/chat-meta 生产版（meta+delta+done+取消处理）。")
public class MyStreamController {

    /** key = 通道名 + "ChatClient"，和 /ai/chat 共用同一批 bean */
    private final Map<String, ChatClient> chatClients;

    /** Token 记账：把每次流式调用的 usage 写进 token_usage 表（查询见 /ai/token/*） */
    private final TokenUsageRecorder tokenRecorder;

    record StreamRequest(String provider, String message) {}

    /** 通道不可用也返回能吐错误的流（流式接口不能抛同步异常就完事，得让前端在流里看到） */
    private ChatClient clientOrThrow(String provider) {
        var client = chatClients.get(provider + "ChatClient");
        if (client == null) throw new IllegalArgumentException("通道不可用：" + provider);
        return client;
    }

    /** ① 裸流：Flux<String> —— 打字机效果最小实现 */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "① 裸流：Flux<String> 打字机",
            description = "返回 Flux<String>，每帧是模型输出的一段（Spring 自动按 SSE 的 data: 帧推）。")
    public Flux<String> chat(@RequestBody StreamRequest body) {
        var provider = body.provider() == null || body.provider().isBlank() ? "dashscope" : body.provider();
        var message = body.message() == null || body.message().isBlank() ? "用一句话介绍什么是数据库索引" : body.message();
        return clientOrThrow(provider).prompt().user(message)
                                      .stream()
                                      .content();
    }

    /** ② 显式 SSE 帧：event 名 + data + 结束帧 [DONE] */
    @PostMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "② 显式 SSE 帧：event=delta + data + event=done + [DONE]",
            description = "把每个片段包成 ServerSentEvent（event=delta），最后补一帧 event=done。可看清 SSE 帧结构。")
    public Flux<ServerSentEvent<String>> sse(@RequestBody StreamRequest body) {
        var provider = body.provider() == null || body.provider().isBlank() ? "dashscope" : body.provider();
        var message = body.message() == null || body.message().isBlank() ? "用一句话介绍什么是数据库索引" : body.message();
        var client = clientOrThrow(provider);
        return Flux.concat(
                client.prompt().user(message)
                      .stream().content()

                      .map(chunk -> ServerSentEvent.builder(chunk).event("delta").build()),
                Flux.just(ServerSentEvent.builder("[DONE]").event("done").build())
        );
    }

    /** ③ 生产版：meta 帧 → delta 帧 → done 帧；done 带 token 统计 + 落库记账；doOnCancel 演示"客户端断开 → 服务端停播" */
    @PostMapping(value = "/chat-meta", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "③ 生产版：meta 帧 + delta 帧 + done 帧（token 随流统计 + 落库记账 + 取消处理）",
            description = "先发 meta（请求回显/通道/开始时间），再逐段推 delta，最后 done（耗时/帧数/token 三件套）。" +
                    "★ 用 stream().chatResponse() 而不是 stream().content()：content 只吐文本丢掉 metadata，" +
                    "chatResponse 每个元素带 metadata，最后一块的 usage 就是本次完整 token 统计（流式拿 token 的正规做法）。" +
                    "doOnCancel 演示前端断连时服务端停播。")
    public Flux<ServerSentEvent<?>> chatMeta(@RequestBody StreamRequest body) {
        var provider = body.provider() == null || body.provider().isBlank() ? "dashscope" : body.provider();
        var message = body.message() == null || body.message().isBlank() ? "用一句话介绍什么是数据库索引" : body.message();
        var client = clientOrThrow(provider);
        var frames = new AtomicInteger();
        // ★ token 记账：流式全程只有最后一块 usage 非 null，用 AtomicReference 一路覆盖，done 帧取最终值
        var usageRef = new AtomicReference<Usage>();
        var modelRef = new AtomicReference<String>();
        long start = System.currentTimeMillis();
        var startTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        return Flux.concat(
                // meta 帧：请求回显 + 通道 + 开始时间（学习台用它展示"实时调用的接口参数"）
                Flux.just(ServerSentEvent.builder(
                        MyMap.of("provider", provider, "message", message, "startTime", startTime)).event("meta").build()),
                // delta 帧：换 chatResponse() 保留 metadata；doOnNext 边流边收集 usage/model（最后一块覆盖前面的 null）
                client.prompt().user(message).stream().chatResponse()
                        .doOnNext(r -> {
                            var md = r.getMetadata();
                            if (md != null) {
                                if (md.getUsage() != null) usageRef.set(md.getUsage());
                                if (md.getModel() != null && !md.getModel().isBlank()) modelRef.set(md.getModel());
                            }
                        })
                        .map(r -> r.getResult().getOutput().getText())   // 只取文本做 delta（usage 汇总块无文本，下面过滤）
                        .filter(t -> t != null && !t.isBlank())
                        .doOnNext(c -> frames.incrementAndGet())
                        .map(chunk -> ServerSentEvent.builder(chunk).event("delta").build()),
                // done 帧：耗时 / 帧数 / token 三件套 + 落库记账
                // ★ 必须用 Flux.defer 惰性构造：Flux.just(...) 的参数在方法执行时就求值了，
                //   那时 frames=0、elapsedMs≈1ms（统计全错）——defer 让"构造 done 帧"推迟到流走完才执行
                Flux.defer(() -> {
                    var u = usageRef.get();
                    var done = new java.util.HashMap<>(MyMap.of(
                            "elapsedMs", System.currentTimeMillis() - start,
                            "frames", frames.get()));
                    if (u != null) {
                        // token 三件套：和 /ai/chat 非流式版同源（getUsage()），流式走"最后一块"拿
                        done.putAll(MyMap.of(
                                "promptTokens", u.getPromptTokens(),          // 输入消耗：提示词(含system、历史)的 token 数
                                "completionTokens", u.getCompletionTokens(),  // 输出消耗：模型生成回答的 token 数
                                "totalTokens", u.getTotalTokens()));          // 总消耗（计费依据）
                        tokenRecorder.record("stream", provider, modelRef.get(), u, System.currentTimeMillis() - start); // 落库记账
                    } else {
                        done.put("note", "厂商未返回 usage，无法统计 token");
                    }
                    return Flux.just(ServerSentEvent.builder(done).event("done").build());
                })
        ).doOnCancel(() -> log.warn("[{}] 客户端断开连接，流已取消，服务端停播（doOnCancel，已吐 {} 帧）", provider, frames.get()));
    }
}
