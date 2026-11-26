package com.example.ai.token;

import com.example.ai.common.MyMap;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * ★ Token 记账 · 查账接口（写账在 TokenUsageRecorder，每次调用落库 token_usage 表）。
 * <p>
 * 从易到难三个档位（knife4j 文档按这个顺序排）：
 * <pre>
 *   1  GET /ai/token/recent   最近调用明细：直接看账本（跑 3 次的数字就从这抄）
 *   2  GET /ai/token/stats    汇总统计：总调用/总 tokens/今日调用/今日 tokens/平均耗时
 *   3  GET /ai/token/daily    按日趋势：近 N 天每天烧了多少（看用量涨没涨）
 * </pre>
 * 教学点：
 *   - 查询全用 JdbcTemplate（和 JdbcChatMemoryRepository 同款，pom 里 spring-boot-starter-jdbc 已引入）
 *   - SQL 聚合三件套：count(*) 行数 / sum(total_tokens) 总量 / avg(elapsed_ms) 均值
 *   - 时间范围用 PG 的 now() - (? * interval '1 day')：interval 不能直接参数化，乘整数天是标准写法
 */
@RestController
@RequestMapping("/ai/token")
@RequiredArgsConstructor
@Tag(name = "Token 记账", description = "每次调用把 token 用量落库（chat/stream 写账见 TokenUsageRecorder），这里查账：①recent 明细 → ②stats 汇总 → ③daily 按日趋势。")
public class TokenController {

    private final JdbcTemplate jdbc;

    /** ① 最近调用明细：直接看账本。面试口述稿「跑 3 次把数字填进去」抄的就是这里 */
    @GetMapping("/recent")
    @Operation(summary = "1 · 最近调用明细", description = "按 id 倒序取最近 N 条（默认 10，上限 50）：时间/接口/通道/模型/三个 token 数/耗时。")
    public Map<String, Object> recent(@RequestParam(defaultValue = "10") int limit) {
        int n = Math.min(Math.max(limit, 1), 50); // 钳制范围：防手滑传 99999 拖垮一次查询
        List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT id, endpoint, provider, model,
                               prompt_tokens, completion_tokens, total_tokens,
                               elapsed_ms, created_time
                        FROM token_usage
                        ORDER BY id DESC
                        LIMIT ?
                        """, n);
        return MyMap.of("ok", true, "count", rows.size(), "rows", rows);
    }

    /** ② 汇总统计：总账 + 今日账，一眼看出「烧了多少、均耗时多少」 */
    @GetMapping("/stats")
    @Operation(summary = "2 · 汇总统计", description = "总调用次数/总 tokens/今日调用/今日 tokens/平均耗时——口径：今日 = 自然日（PG date_trunc('day', now())）。")
    public Map<String, Object> stats() {
        // 总账：行数 + 总 token（COALESCE 防空表返回 null）
        Map<String, Object> total = jdbc.queryForMap("""
                        SELECT count(*) AS calls,
                               COALESCE(sum(total_tokens), 0) AS tokens
                        FROM token_usage
                        """);
        // 今日账：created_time 从今天 0 点起（date_trunc 是 PG 截断时间的标准函数）
        Map<String, Object> today = jdbc.queryForMap("""
                        SELECT count(*) AS calls,
                               COALESCE(sum(total_tokens), 0) AS tokens
                        FROM token_usage
                        WHERE created_time >= date_trunc('day', now())
                        """);
        // 平均耗时：只统计有耗时的行
        Long avgElapsed = jdbc.queryForObject(
                "SELECT COALESCE(avg(elapsed_ms), 0) FROM token_usage WHERE elapsed_ms IS NOT NULL",
                Long.class);
        return MyMap.of(
                "ok", true,
                "totalCalls", total.get("calls"),        // 历史累计调用次数
                "totalTokens", total.get("tokens"),      // 历史累计 token（计费依据）
                "todayCalls", today.get("calls"),        // 今天（自然日）调用次数
                "todayTokens", today.get("tokens"),      // 今天消耗的 token
                "avgElapsedMs", avgElapsed               // 平均单次耗时（毫秒）
                       );
    }

    /** ③ 按日趋势：近 N 天每天烧了多少（看用量涨没涨、接口分布） */
    @GetMapping("/daily")
    @Operation(summary = "3 · 按日趋势", description = "近 N 天（默认 7）按自然日聚合：每天调用次数 + token 总量，按天正序返回。")
    public Map<String, Object> daily(@RequestParam(defaultValue = "7") int days) {
        int d = Math.min(Math.max(days, 1), 30); // 钳制：最多看一个月
        List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT to_char(created_time, 'MM-DD') AS day,
                               count(*) AS calls,
                               COALESCE(sum(total_tokens), 0) AS tokens
                        FROM token_usage
                        WHERE created_time >= now() - (? * interval '1 day')  -- interval 不能参数化，乘整数天是标准写法
                        GROUP BY day
                        ORDER BY day
                        """, d);
        return MyMap.of("ok", true, "days", d, "rows", rows);
    }
}
