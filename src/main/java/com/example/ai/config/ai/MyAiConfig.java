package com.example.ai.config.ai;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.ai.chat.memory.JdbcChatMemoryRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * My* = 本工程「自己写的」类（与 starter jar 里的类区分开，命名约定见 MsAiApplication）。
 *
 * <p><b>这里现在是「装配总览 + 公共设施」。</b>三个通道的具体 bean 拆到各通道配置类：
 * <ul>
 *   <li>通道 A：DashScope 原生（Spring AI Alibaba）→ {@link DashScopeChannelConfig}</li>
 *   <li>通道 B：OpenAI 兼容（官方 starter + 百炼兼容端点）→ {@link OpenAiChannelConfig}</li>
 *   <li>通道 C：硅基流动（免费开源模型）→ {@link SiliconFlowChannelConfig}</li>
 * </ul>
 * 本类只留三样【所有通道共用】的东西：默认系统提示词 {@link #DEFAULT_SYSTEM}、
 * embedding 分批工具 {@link #batchByCount(int)}、多轮对话记忆 {@link #chatMemory()}。
 *
 * <p>================= 这张图先记住 =================
 * <pre>
 *   ChatModel（模型本体，starter jar 提供，只会 .call(prompt)）      ← 不是我们写的
 *       ↓ ChatClient.builder(model) 包装                          ← 下面这些 bean 是我们写的
 *   ChatClient（流式 API：.prompt().user("...").call().content()）
 *
 *   EmbeddingModel（文本 → 向量，starter jar 提供）
 *       ↓ SimpleVectorStore.builder(embeddingModel)
 *   VectorStore（存向量 + 相似度检索）   ← RAG 的地基
 * </pre>
 *
 * <p>================= 为什么要手工 new ChatClient =================
 * application.yml 里把 spring.ai.chat.client.enabled 设成了 false。
 * 原因：Spring AI 的自动配置默认只认一个 ChatModel，两个的时候它不知道挑谁，
 * 直接抛 NoUniqueBeanDefinitionException 让服务起不来。
 * → 关掉自动装配，自己显式建两个，哪个通道用哪个 model 一目了然。
 *
 * <p>================= bean 命名的坑 =================
 * VectorStore 的 bean 名 = 通道名（dashscope / openai / siliconflow）。
 * MyKnowledgeLoader 用 {@code Map<String, VectorStore>} 一把收齐做灌库，
 * MyRagController 同样用 Map 按名字取 —— 改各通道类里的 bean 名要同步改那两处。
 *
 * <p>================= ★★ 这里踩过一个非常有教学价值的坑，记牢 ★★ =================
 * 最初我给每个 @Bean 加了 {@code @ConditionalOnBean(DashScopeChatModel.class)}，
 * 想着「模型 bean 不存在就不装 Embedder」。结果服务起来了，但所有 bean 一个都没创建，
 * 启动日志里四个管道全部报：
 * <pre>@ConditionalOnBean (types: ...DashScopeChatModel) did not find any beans of type ...</pre>
 * 原因：@ConditionalOnBean 判定的是【此刻容器里已注册的 bean】，而
 * <b>普通 @Configuration 的处理顺序早于所有 auto-configuration</b>；
 * DashScopeChatModel 恰恰是 DashScope 的 auto-config 创建的，此时还没注册，条件永远不成立。
 * <p>结论：@ConditionalOnBean 只该用在 auto-configuration（{@code @AutoConfiguration}）里。
 * 应用自己的 @Configuration 里老老实实直接注入，缺 bean 就让启动失败（Fail Fast），
 * 比「静默少一堆 bean、跑到一半才发现 RAG 空库」要好排查一万倍。
 * （完整推演见教学页 16「SpringBoot装配顺序」。）
 */
@Configuration
public class MyAiConfig {

    /** 系统提示词：Hello World 阶段给个轻量人设，方便验证它确实生效了。三个通道的 ChatClient 都用它 */
    public static final String DEFAULT_SYSTEM =
            "你是部署在一台 2C2G 云服务器上的自学助手，回答简洁务实，能用代码说明就不要空谈。";

    /**
     * 按条数切分的 embedding 分批策略（n=每批最多几条），规避厂商单请求条数上限。
     * 三通道的 PgVectorStore 都用它（各厂商 embedding 单请求条数上限不同，统一 10 条最稳）。
     */
    public static BatchingStrategy batchByCount(int n) {
        return docs -> {
            var batches = new ArrayList<List<Document>>();
            for (int i = 0; i < docs.size(); i += n) {
                batches.add(docs.subList(i, Math.min(i + n, docs.size())));
            }
            return batches;
        };
    }

    /**
     * ★ 第 5 档「多轮对话」用的记忆（ChatMemory）。
     * 官方内存实现 MessageWindowChatMemory：按 conversationId 存消息，只留最近 maxMessages 条
     * （滑动窗口，防历史无限膨胀、token 越聊越多）。
     * <p>
     * 自动配置里也带一个 ChatMemory（默认窗口），但那是 @ConditionalOnMissingBean——
     * 我们自己注册一个（并注释清楚窗口含义），学习效果更好，也不冲突。
     */
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder().maxMessages(10).build();
    }

    /**
     * ★ 生产化记忆底座（重启不丢）：把消息落进 PostgreSQL（chat_memory 表）。
     * 和 {@link #chatMemory()} 内存版做对照——同一个 MessageWindowChatMemory，只是「存哪」从内存换成 PG。
     * 多轮对话想持久化，就把这个 bean 喂给 MessageChatMemoryAdvisor；内存版 /memory 保留用于对照。
     */
    @Bean("chatMemoryPg")
    public ChatMemory chatMemoryPg(JdbcTemplate jdbcTemplate) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new JdbcChatMemoryRepository(jdbcTemplate))
                .maxMessages(10)
                .build();
    }
}
