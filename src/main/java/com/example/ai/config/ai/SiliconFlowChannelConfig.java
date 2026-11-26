package com.example.ai.config.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 通道 C：硅基流动（免费开源模型）。
 * 硅基流动是 OpenAI 兼容端点 → 复用 openai starter 的类 + 自定义 base-url = 接任何兼容厂商的通用姿势。
 *
 * <p>★ 两个教学级坑（已踩过）：
 * <ul>
 *   <li>bean 声明类型必须用接口（ChatModel / EmbeddingModel）——若声明 OpenAiChatModel，
 *       openai 自动装配的 @ConditionalOnMissingBean 会以为「已有」而跳过创建老通道 bean，
 *       启动报 No qualifying bean（详见 MyAiConfig 头注释）。</li>
 *   <li>baseUrl 已写全到 /v1，completionsPath/embeddingsPath 必须用相对路径，
 *       否则拼成 /v1/v1/chat/completions → 404。</li>
 * </ul>
 */
@Configuration
public class SiliconFlowChannelConfig {

	/**
	 * 硅基流动 OpenAI 兼容端点：base-url 已含 /v1（没有"重复 v1"的坑，见下）
	 */
	private static final String SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1";
	/**
	 * 免费 chat 模型（2026-10 实测可用；glm-4-9b-chat / internlm2_5-7b-chat 已下架 30003 Model disabled）
	 */
	private static final String SILICONFLOW_CHAT_MODEL = "Qwen/Qwen2.5-7B-Instruct";
	/**
	 * 免费 embedding 模型：bge-m3，1024 维 —— 和 pgvector 表维度一致，不用重建表
	 */
	private static final String SILICONFLOW_EMBEDDING_MODEL = "BAAI/bge-m3";

	/**
	 * 第三通道的模型本体（OpenAI 兼容 SDK + 自定义 base-url = 接任何兼容厂商的通用姿势）。
	 * ★ 返回类型故意声明成 ChatModel 而不是 OpenAiChatModel——openai 自动装配的
	 * openAiChatModel 方法带 @ConditionalOnMissingBean（无参数=按返回类型判），
	 * 若这里声明 OpenAiChatModel，自动装配会认为「已有这个 bean」直接跳过创建，
	 * 老通道就全没了（启动报 No qualifying bean）。声明成接口类型则不触发抑制。
	 */
	@Bean("siliconflowChatModel")
	public ChatModel siliconflowChatModel(@Value("${msai.siliconflow.api-key:}") String apiKey) {
		// ★ 重复 /v1 的坑：baseUrl 已写全到 /v1，路径必须用相对路径，否则拼成 /v1/v1/chat/completions → 404
		var api = OpenAiApi.builder()
		                   .baseUrl(SILICONFLOW_BASE_URL)
		                   .apiKey(apiKey)
		                   .completionsPath("/chat/completions")
		                   .embeddingsPath("/embeddings")
		                   .build();
		return OpenAiChatModel.builder()
		                      .openAiApi(api)
		                      .defaultOptions(OpenAiChatOptions.builder()
		                                                       .model(SILICONFLOW_CHAT_MODEL)
		                                                       .build())
		                      .build();
	}

	@Bean("siliconflowChatClient")
	public ChatClient siliconflowChatClient(@Qualifier("siliconflowChatModel") ChatModel model) {
		return ChatClient.builder(model)
		                 .defaultSystem(MyAiConfig.DEFAULT_SYSTEM)
		                 .build();
	}

	@Bean("siliconflowEmbeddingModel")
	public EmbeddingModel siliconflowEmbeddingModel(@Value("${msai.siliconflow.api-key:}") String apiKey) {
		// ★ 重复 /v1 的坑同 ChatModel
		var api = OpenAiApi.builder()
		     .baseUrl(SILICONFLOW_BASE_URL)
		     .apiKey(apiKey)
		     .completionsPath("/chat/completions") // 聊天补全接口的相对路径，与 baseUrl 拼接后为 /v1/chat/completions
		     .embeddingsPath("/embeddings") // 文本嵌入接口的相对路径，与 baseUrl 拼接后为 /v1/embeddings（★必须用相对路径，避免与 baseUrl 的 /v1 重复导致 404）
		     .build();
		return new OpenAiEmbeddingModel(
				api,
				MetadataMode.EMBED,
				OpenAiEmbeddingOptions.builder()
				                      .model(SILICONFLOW_EMBEDDING_MODEL)
				                      .build()
		);
	}

	@Bean("siliconflowVectorStore")
	public VectorStore siliconflowVectorStore(@Qualifier("siliconflowEmbeddingModel") EmbeddingModel embeddingModel) {
		return SimpleVectorStore.builder(embeddingModel)
		                        .build();
	}

	@Bean("siliconflowVectorStorePg")
	public VectorStore siliconflowVectorStorePg(
			JdbcTemplate jdbcTemplate,
			@Qualifier("siliconflowEmbeddingModel") EmbeddingModel embeddingModel
	                                           ) {
		return PgVectorStore.builder(
				                    jdbcTemplate,
				                    embeddingModel
		                            )
		                    .idType(PgVectorStore.PgIdType.TEXT)
		                    .dimensions(1024)               // bge-m3 = 1024 维，与现有表一致
		                    .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
		                    .indexType(PgVectorStore.PgIndexType.HNSW)
		                    .initializeSchema(true)
		                    .batchingStrategy(MyAiConfig.batchByCount(10))
		                    .maxDocumentBatchSize(10)
		                    .build();
	}
}
