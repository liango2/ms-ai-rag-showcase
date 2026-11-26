package com.example.ai.common;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ★ Token 记账组件：把「每次模型调用的 token 用量」写进 PostgreSQL 表 token_usage。
 * <p>
 * 来龙去脉：/ai/chat 和 /ai/stream/chat-meta 都从 ChatResponse 的 metadata 拿到
 * TokenUsage（prompt/completion/total 三个数）——拿到之后干嘛？之前是随响应返回一次就丢；
 * 这里多走一步：落库。落库之后就能查账（/ai/token/*）：
 * 「今天花了多少 token」「哪个接口烧得多」「跑了 3 次分别是多少」——面试口述稿的数字就是这么来的。
 * <p>
 * 只负责「写」；「查」在 TokenController。写失败不阻断业务（try-catch 吞掉，记账是锦上添花）。
 */
@Component
public class TokenUsageRecorder {

    private final JdbcTemplate jdbc;

    public TokenUsageRecorder(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 记一笔账。
     *
     * @param endpoint 哪个接口：chat（非流式）/ stream（流式）
     * @param provider 通道名：dashscope / openai / siliconflow
     * @param model    实际模型名（可为 null，表允许空）
     * @param usage    Usage：三个 token 数都从它身上取；null 则跳过（厂商没给 usage 不记）
     * @param elapsedMs 本次调用耗时（可为 null）
     */
    public void record(String endpoint, String provider, String model, Usage usage, Long elapsedMs) {
        if (usage == null) return; // 厂商没返回 usage（如某些流式实现）→ 不记账，避免一行全 0
        try {
            jdbc.update("""
                            INSERT INTO token_usage(endpoint, provider, model, prompt_tokens, completion_tokens, total_tokens, elapsed_ms)
                            VALUES (?,?,?,?,?,?,?)
                            """,
                    endpoint, provider, model,
                    usage.getPromptTokens(),   // 输入消耗：提示词(含system、历史)的 token 数
                    usage.getCompletionTokens(), // 输出消耗：模型生成回答的 token 数
                    usage.getTotalTokens(),    // 总消耗 = 前两者之和（计费依据）
                    elapsedMs);
        } catch (Exception e) {
            // 记账失败不影响主流程：日志留痕即可（面试可答：记账是可观测性的旁路，不能反噬业务）
            System.err.println("[TokenUsageRecorder] 记账失败（已忽略）: " + e.getMessage());
        }
    }
}
