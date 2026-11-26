package com.example.ai.config.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 通道 B：OpenAI 兼容（官方 spring-ai-openai starter + 百炼兼容端点）。
 *
 * <p>★ 下面所有 @Qualifier 都是「第三通道」加的保险：容器里现在有两个 OpenAiChatModel /
 * 两个 OpenAiEmbeddingModel（auto-config 的 openAiChatModel + 硅基流动的 siliconflowChatModel），
 * 按类型注入会 NoUniqueBeanDefinitionException，必须点名要哪个。
 * auto-config 的 bean 名固定为 <b>openAiChatModel / openAiEmbeddingModel</b>（方法名）。
 */
@Configuration
public class OpenAiChannelConfig {

    // 冗余的 openaiChatModel 透传别名已删除：controller 用 chatModelBean("openai")→openAiChatModel 直接取 auto-config 真实 bean
    @Bean("openaiChatClient")
    public ChatClient openaiChatClient(
            @Qualifier("openAiChatModel") OpenAiChatModel model) {
        return ChatClient.builder(model)
                         .defaultSystem(MyAiConfig.DEFAULT_SYSTEM)
                         .build();
    }

    @Bean("openaiVectorStore")
    public VectorStore openaiVectorStore(
            @Qualifier("openAiEmbeddingModel") OpenAiEmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel)
                                .build();
    }

    @Bean("openaiVectorStorePg")
    public VectorStore openaiVectorStorePg(
            JdbcTemplate jdbcTemplate,
            @Qualifier("openAiEmbeddingModel") OpenAiEmbeddingModel embeddingModel
                                          ) {
        return PgVectorStore.builder(
                                    jdbcTemplate,
                                    embeddingModel
                                    )
                            .idType(PgVectorStore.PgIdType.TEXT)          // ★ 文档 id 是 "ms-ai-1" 字符串，默认 UUID 会解析炸
                            .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                            .dimensions(1024)   // ★ text-embedding-v3 默认 1024 维；显式写死，避免模型探维度失败时兜底 1536 建错表
                            .indexType(PgVectorStore.PgIndexType.HNSW)
                            .initializeSchema(true)
                            .batchingStrategy(MyAiConfig.batchByCount(10))
                            .maxDocumentBatchSize(10)
                            .build();
    }
}
