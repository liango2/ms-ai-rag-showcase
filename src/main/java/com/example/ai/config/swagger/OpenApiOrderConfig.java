package com.example.ai.config.swagger;

import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.PathItem;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 接口文档展示顺序：让 knife4j 按「学习顺序」排，而不是按字母/扫描顺序。
 * <p>
 * 为什么需要它：
 *   ① springdoc 生成的 /v3/api-docs 里 paths 是乱序的（扫描顺序，跟源码声明不一致）；
 *   ② swagger-ui 默认按字母排序，把 once/providers/stream 打散。
 *   所以用 GlobalOpenApiCustomizer 做两件事：
 *     ① 重排左侧目录（分组 tag）顺序 → TAG_ORDER；
 *     ② 重排组内接口（paths）顺序 → PATH_ORDER；
 *   再配 application.yml 里 springdoc.swagger-ui.operations-sorter: method
 *   （同一 tag 内先 GET 后 POST，同方法的保持本表顺序），knife4j 页就按此展示了。
 * <p>
 * ★ 学习顺序的基准：前端 index.html 的 tab 顺序
 *   （💬Chat → 🧠记忆(在Chat档5) → 📚RAG → 🔧工具 → 🧩结构化 → 🖥️流式 → 🤖Agent → 📊Token记账）。
 *   分组没有独立"记忆"（在 Chat 档位5/6），所以 tag 顺序与 tab 一一对应去掉 memory。
 */
@Configuration
public class OpenApiOrderConfig {

    /** 左侧目录（分组）学习顺序：与前端 tab 一致；未列出的分组（如 LearnDocController 默认 tag）兜底排最后 */
    private static final List<String> TAG_ORDER = List.of(
            "Chat 对话接口",                    // 💬 对话主线 5 档（1探路→2非流式→3流式→4生产化→5/6多轮记忆）
            "RAG 检索",                        // 📚 灌库→加文档→问答→统计
            "Tool 工具调用",                    // 🔧 Function Calling 基础
            "结构化输出 Structured Output",     // 🧩 强制按 POJO 格式返回
            "流式输出 Streaming",               // 🖥️ SSE 三档（裸流→显式帧→生产版带 token）
            "Agent 智能体",                    // 🤖 基于工具调用的自主编排
            "Token 记账",                      // 📊 每次调用 token 落库 + 查账（可观测收尾）
            "面试练习"                          // 🎯 出题→作答→双栏对比 + 闪卡进度（面试备战收尾）
                                                       );

    /** 组内接口学习顺序：Chat 5 档 → RAG 4 步 → 工具 → 结构化 → 流式 3 档 → Agent → Token 记账 */
    private static final List<String> PATH_ORDER = List.of(
            "/ai/chat/providers",        // Chat 档1 · 探路：看有哪些通道
            "/ai/chat/once",             // Chat 档2 · 非流式最简对话
            "/ai/chat/stream",           // Chat 档3 · 流式对话（SSE）
            "/ai/chat",                  // Chat 档4 · 生产化完整版（耗时/token/模型名）
            "/ai/chat/memory",           // Chat 档5 · 多轮记忆（内存版）
            "/ai/chat/memory-pg",        // Chat 档6 · 多轮记忆（PG 落库版）
            "/ai/rag/reload",            // RAG 1 · 重灌向量库
            "/ai/rag/add",               // RAG 2 · 加文档
            "/ai/rag/ask",               // RAG 3 · 问答
            "/ai/rag/stats",             // RAG 4 · 看统计
            "/ai/tools/ask",             // 工具 · Function Calling 问答
            "/ai/struct/entity",         // 结构化 · 强类型 DTO（entity）
            "/ai/struct/list",           // 结构化 · 列表 DTO（list）
            "/ai/stream/chat",           // 流式 1 · 裸流 Flux<String>
            "/ai/stream/sse",            // 流式 2 · 显式 SSE 帧（delta+done）
            "/ai/stream/chat-meta",      // 流式 3 · 生产版（meta+delta+done+token+记账）
            "/ai/agent/travel",          // Agent · 旅行规划（查时间+天气）
            "/ai/agent/refund",          // Agent · 订单售后决策（查订单+查政策）
            "/ai/token/recent",          // 记账 1 · 最近调用明细
            "/ai/token/stats",           // 记账 2 · 汇总统计
            "/ai/token/daily",           // 记账 3 · 按日趋势
            "/ai/interview/papers",      // 面试 1 · 卷列表与进度
            "/ai/interview/questions",   // 面试 2 · 按卷出题
            "/ai/interview/answer/{id}",     // 面试 3 · 保存我的回答
            "/ai/interview/answer-both/{id}",// 面试 4 · 双栏对比保存
            "/ai/interview/flashcards",  // 面试 5 · 闪卡学习进度
            "/ai/interview/flashcard/{cardKey}" // 面试 6 · 闪卡复习打卡
                                                          );

    @Bean
    public GlobalOpenApiCustomizer orderPaths() {
        return openApi -> {
            // ① 左侧目录（分组）按学习顺序排：TAG_ORDER 里的顺序，未列出的 tag 兜底放最后
            if (openApi.getTags() != null) {
                openApi.getTags().sort(Comparator.comparingInt(t -> {
                    int i = TAG_ORDER.indexOf(t.getName());
                    return i < 0 ? TAG_ORDER.size() : i;
                }));
            }
            // ② 组内接口（paths）按学习顺序排：PATH_ORDER 里的顺序，未列出的路径兜底放最后
            Map<String, PathItem> paths = openApi.getPaths();
            if (paths == null) return;
            Paths ordered = new Paths();
            for (String p : PATH_ORDER) {
                if (paths.containsKey(p)) ordered.addPathItem(p, paths.get(p));
            }
            // 未列出的路径兜底追加在最后，避免丢接口
            paths.forEach((k, v) -> {
                if (!ordered.containsKey(k)) ordered.addPathItem(k, v);
            });
            openApi.setPaths(ordered);
        };
    }
}
