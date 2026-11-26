# ms-ai —— Spring AI 自学工程（Chat + RAG 双通道对照）

> 本工程位于 **ms-demo 仓库的 `ms-ai/` 目录**（2026-10-06 从 `C:\Users\hanliangyuan\ms-ai` 搬来，
> 目的是和微服务代码一起查看、一次提交）。
> 但注意：**只是物理位置相邻，不是 Maven 子模块** —— 本工程 parent 是 spring-boot-starter-parent，
> 也没被写进根 pom 的 `<modules>`，所以必须 `cd ms-ai` 单独构建；
> 在 ms-demo 根目录跑 `mvn package` 不会构建本工程（这是刻意的版本隔离）。
>
> 云机 `101.43.45.217` 上独立进程运行（8086），nginx `location /ai/` 反代，
> 访问入口：`http://101.43.45.217/ai/`（门户首页也有卡片）。

## 版本组合（2026-10-05 钉死，改版本前先查兼容矩阵）

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Spring Boot | 3.5.16 | ms-demo 还在 3.3.5，Spring AI 1.1.x 要求 3.5.x，**这是不能做成 ms-demo 子模块的根因** |
| Spring AI | 1.1.8 | 1.x 主线最新；2.x 需要 Boot 4.x，不要混 |
| Spring AI Alibaba | 1.1.2.3 | DashScope 原生 starter；**其 BOM 不管理 starter 版本，必须显式写** |
| JDK | 本机 22 / 云机 17 | 字节码按 17 打 |
| 向量库 | SimpleVectorStore（内存） | 重启即丢，正式语料放 knowledge/ 目录启动自动重灌 |

## 双通道设计（对照实验）

同一个百炼 Key、同一个 qwen-plus、同一个 text-embedding-v3，唯一变量是 SDK：

| 通道 | SDK | base-url | 特点 |
| --- | --- | --- | --- |
| dashscope | spring-ai-alibaba-starter-dashscope | 百炼原生 | 国内直连；**chat 返回的 metadata 里没有 model 名**（已用配置值兜底） |
| openai | spring-ai-starter-model-openai | 百炼 OpenAI 兼容端点 | 通用性强；metadata 带 model 名 |

页面选 `both` 时两个通道并行发问，并排对比答案、耗时、token。

## 关键接口

```
GET  /ai/chat/providers      可用通道
POST /ai/chat                {provider: dashscope|openai|both, message, system?}
POST /ai/rag/ask             {provider, question, topK, mode: manual|advisor}
GET  /ai/rag/stats           知识库状态（含 loading）
POST /ai/rag/reload          重新灌库（改了 knowledge/ 目录后调）
POST /ai/rag/add             {source, text} 临时加一段（重启丢失）
```

## 目录

```
ms-ai/
├── src/main/resources/knowledge/   内置语料（Spring AI 概念速记，classpath 加载）
├── deploy/knowledge-seed/          你的 ms-demo 学习笔记（部署到云机 /root/ms-ai/knowledge/）
├── deploy/server/10-start-ms-ai.sh 云机启停脚本（start|stop|restart|status）
├── deploy/deploy.env.example       环境变量模板（真 Key 只在云机 /root/ms-ai/deploy.env）
└── src/main/resources/static/      学习台页面（nginx /ai/ 直接打开）
```

## 部署流程（jar 更新）

```bash
# 本机 —— 注意必须在 ms-ai 目录里单独构建
# （在 ms-demo 根目录 mvn package 不会带你，本工程不是它的子模块）
cd ms-demo/ms-ai
mvn -B package -DskipTests
scp target/ms-ai.jar msdemo:/tmp/ms-ai.jar
# 云机：先停 → 再覆盖 → 再启动（顺序别反，覆盖运行中的 jar 会损坏）
ssh msdemo "sudo bash /root/ms-ai/deploy/10-start-ms-ai.sh stop \
  && sudo cp /tmp/ms-ai.jar /root/ms-ai/ms-ai.jar \
  && sudo bash /root/ms-ai/deploy/10-start-ms-ai.sh start"
# 验证
ssh msdemo "sudo bash /root/ms-ai/deploy/10-start-ms-ai.sh status"
```

加/改语料：改本机 `deploy/knowledge-seed/` → tar 打包 scp 到云机 `/root/ms-ai/knowledge/`
→ `curl -X POST http://127.0.0.1:8086/ai/rag/reload`（不用重启服务）。

## 踩坑实录（都已修复并写进代码注释）

1. **`@ConditionalOnBean` 用在普通 `@Configuration` 里 = 静默失效**。
   它评估时机早于 auto-configuration，永远看不到后者的 bean → 全部 bean 被跳过，
   服务照常启动但 ChatClient/VectorStore 一个都没有。教训：这个注解只配用在
   `@AutoConfiguration` 里；应用配置直接注入，缺 bean 就 Fail Fast。
2. **OpenAI 兼容端点的「重复 /v1」**：starter 默认 path 自带 `/v1/embeddings`，
   base-url 再写 `.../compatible-mode/v1` 就拼成 `/v1/v1/embeddings` → 404。
   本工程解法：base-url 写全 + 显式 `completions-path: /chat/completions`、
   `embeddings-path: /embeddings`。★ path 必须带前导斜杠，少斜杠同样 404。
3. **Spring AI Alibaba 的 BOM 是空壳**：只管 graph/agent 模块，
   dashscope starter 版本必须自己写，否则 `dependency.version is missing`。
4. **双 ChatModel 共存**：必须 `spring.ai.chat.client.enabled=false` 关掉自动
   ChatClient.Builder（两个 model 会让它 NoUniqueBeanDefinitionException），
   然后手工各建一个，bean 名 = `dashscopeChatClient` / `openaiChatClient` /
   `dashscopeVectorStore` / `openaiVectorStore`（Controller 用 Map 注入按名取用）。
5. **同步灌库会拖死启动**：89 片段 × 2 通道 ≈ 29s。已改异步，
   `/ai/rag/stats` 有 loading 字段。
6. **SimpleVectorStore.add 是追加**：reload 不先删会成倍堆积，已按 `app == 'ms-ai'`
   filter 先删后灌。

## 与 ms-demo 的关系

- 不注册 Nacos、不进网关，nginx 直接反代（`location = /ai/` 剥前缀给页面、
  `location ^~ /ai/` 保前缀给 API —— 因为 Controller 路径本身带 /ai）。
- JVM 参数在 `10-start-ms-ai.sh`：`-Xmx320m -XX:+UseSerialGC`，实测 RSS ≈ 220MB。
- 内存紧张时可停 ms-demo 的 user/order-service 腾出 ~270MB（ms-ai 单独跑没问题）。

## 待办（下一步学习路线）

- [ ] 换正式向量库（pgvector / Redis），对比检索质量与延迟
- [ ] ChatMemory 多轮对话（starter 已带 chat-memory 依赖）
- [ ] 工具调用 Tool Calling（让模型调 demo 的 /api/weather）
- [ ] 流式输出 streaming（页面打字机效果）
- [ ] RAG 升级：按 Markdown 标题切分、混合检索（BM25 + 向量）
