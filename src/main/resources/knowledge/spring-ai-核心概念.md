# Spring AI 核心概念速记（内置示例语料）

这份文档是 `ms-ai` 工程的内置示例语料，用来验证 RAG 链路是否通畅：
如果你问「ChatClient 和 ChatModel 有什么区别」，应当能检索到下面第 2 条。

## 1. Spring AI 是什么

Spring AI 是 Spring 官方的 AI 应用开发框架，目标是让 Java 开发者用熟悉的 Spring 风格
（依赖注入、自动装配、模板 API）去调用大模型，而不用自己手写 HTTP 请求和 JSON 解析。

它统一抽象了四类能力：Chat（对话）、Embedding（向量化）、Image（图像生成）、
Vector Store（向量检索），上层再提供 ChatClient 流式 API、Advisor 拦截器、工具调用等。

版本对应关系很关键：Spring AI 1.x 需要 Spring Boot 3.5.x；
Spring AI 2.x 需要 Spring Boot 4.x。老项目（Boot 3.3 及以下）不能直接用最新版，
这也是 ms-ai 单独开一个工程、使用 Boot 3.5.16 的原因。

## 2. ChatModel 和 ChatClient 的区别

ChatModel 是模型本体接口，职责单一：接收 Prompt，返回 ChatResponse。
常见实现有 DashScopeChatModel、OpenAiChatModel。

ChatClient 是对 ChatModel 的流式封装，提供链式调用：
`chatClient.prompt().system("你是助手").user("你好").call().content()`。
它额外提供 Advisor（拦截器）、默认系统提示词、结构化输出、流式返回等能力。

一句话：ChatModel 管「怎么调模型」，ChatClient 管「怎么把调用写得顺手」。

两个实现同时存在时，Spring AI 自带的 ChatClient.Builder 自动装配会因为候选 bean 不唯一
而报错 NoUniqueBeanDefinitionException。解决办法是在配置文件里关闭
`spring.ai.chat.client.enabled=false`，然后手工为每个模型创建 ChatClient。

## 3. Embedding 是什么

Embedding 模型把一段文本转换成一个高维浮点向量（例如 1024 维），
语义相近的文本在空间里距离也近。检索时把用户问题同样转成向量，
再和库里的向量算余弦相似度，取最相近的若干条。

百炼的 text-embedding-v3 输出 1024 维向量。

重要约束：不同 Embedding 模型生成的向量不互通，
DashScope 生成的向量不能用 OpenAI 通道去检索，所以每个通道必须有自己的 VectorStore。

## 4. VectorStore 与 SimpleVectorStore

VectorStore 接口定义了 add（写入）和 similaritySearch（检索）两个核心操作。

SimpleVectorStore 是内存实现，数据存在 JVM 堆里，重启即丢失，
适合 Hello World 和小规模演示；生产环境一般换成 PostgreSQL + pgvector、
Redis Stack、Milvus 或 Elasticsearch。

它的 add 方法是追加语义，重复灌库会让同一片段成倍堆积，
所以在 reload 时要先按标记删除旧数据再写入。

## 5. RAG 的流程

RAG（Retrieval Augmented Generation，检索增强生成）分离线和在线两段：

离线：文档切分 -> Embedding 转向量 -> 存入 VectorStore。
在线：问题转向量 -> 相似度检索 topK 片段 -> 拼进 Prompt -> 模型作答。

RAG 的价值在于：模型不必「记住」私有知识，只要检索得到正确片段就能回答，
同时答案可以附带出处，具备可解释性。

效果好不好，八成取决于第一步的文档切分。英文常用的 TokenTextSplitter
对中文效果一般，中文更适合按自然段落 + 滑窗重叠的方式切。

## 6. 常见坑小结

版本坑：Spring AI 1.1.x 与 Spring Boot 3.5.x 搭配，2.x 才配 Boot 4.x，
凭印象选版本很容易编译失败。

包名坑：1.1.x 里 ChatResponse 在 org.springframework.ai.chat.model 包下，
早期版本的 org.springframework.ai.chat.response 已经不存在。

依赖坑：Spring AI Alibaba 的 BOM 并不管理 DashScope starter 的版本，
starter 的版本号必须显式声明，否则报 dependency.version is missing。

输出坑：不同 SDK 上报的 token 用量字段可能为空，取值时要判空，
不能假设 usage 一定存在。
