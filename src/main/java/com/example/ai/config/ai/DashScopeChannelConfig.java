package com.example.ai.config.ai;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
// ★ 注意包名里有个 text 子包，写少这一层编译不过（已从 jar 里核对）
import com.alibaba.cloud.ai.dashscope.embedding.text.DashScopeEmbeddingModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 通道 A：DashScope 原生（Spring AI Alibaba）。
 * 三个通道各自独立 @Configuration，bean 名 = 通道名，控制器用 Map 按名取，谁都不抢谁的。
 */
@Configuration
public class DashScopeChannelConfig {

    /**
     * ChatClient 别名 bean。
     * 为什么单独包一层 ChatClient：ChatClient 自带 .prompt() 链式 API，比直接调 ChatModel 顺手；
     * 流式(.stream())只有 ChatModel 才有 —— 需要模型本体时，MyChatController 直接按
     * auto-config 的真实 bean 名 <b>dashScopeChatModel</b>（S 大写，见 chatModelBean() 映射）取，
     * 这里不再重复注册 ChatModel 别名（那只是透传，纯冗余）。
     */
    @Bean("dashscopeChatClient")
    public ChatClient dashscopeChatClient(DashScopeChatModel model) {
        return ChatClient.builder(model).defaultSystem(MyAiConfig.DEFAULT_SYSTEM).build();
    }

    @Bean("dashscopeVectorStore")
    public VectorStore dashscopeVectorStore(DashScopeEmbeddingModel embeddingModel) {
        // ★ builder 的第一个参数就是 EmbeddingModel（不是无参 builder，1.1.x 已核对）
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    /**
     * ★ 第 6 档「生产化向量库」：DashScope 通道的 pgvector 存储。
     * <p>
     * 和 SimpleVectorStore 的区别：
     *   <ul>
     *     <li>落盘持久化（PostgreSQL），服务重启知识库不丢 —— 内存版一重启就归零；</li>
     *     <li>有真正的 ANN 索引（HNSW），数据量大时检索不退化 —— 内存版是暴力全量扫；</li>
     *     <li>能用 SQL 做「向量相似度 + 元数据过滤」混合检索。</li>
     *   </ul>
     * <p>
     * bean 名带 <b>Pg</b> 后缀 → 和内存版分开，Map&lt;String,VectorStore&gt; 里两个都在，
     * RAG 接口按 store=memory|pg 参数二选一，同页对比检索质量与延迟。
     * <p>
     * JdbcTemplate/DataSource 由 spring-boot 依据 spring.datasource.* 自动装配；
     * PgVectorStore 用 initializeSchema=true 在启动时自动建表（建表语句见仓库 db/pgvector-init.sql）。
     */
    @Bean("dashscopeVectorStorePg")
    public VectorStore dashscopeVectorStorePg(JdbcTemplate jdbcTemplate, DashScopeEmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .idType(PgVectorStore.PgIdType.TEXT)          // ★ 文档 id 是 "ms-ai-1" 字符串，默认 UUID 会解析炸
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .dimensions(1024)   // ★ text-embedding-v3 默认 1024 维；显式写死，避免模型探维度失败时兜底 1536 建错表
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                // ★ DashScope embedding 单次请求最多 10 条（实测 HTTP 400 提示）：
                //   PgVectorStore 默认按 token 数分大包，一次塞 92 条会被拒。
                //   这里改成按条数每 10 条一批；SimpleVectorStore 是逐条 embed，所以一直没事。
                .batchingStrategy(MyAiConfig.batchByCount(10))
                .maxDocumentBatchSize(10)
                .build();
    }
}
