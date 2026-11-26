package com.example.ai.rag;

import com.example.ai.common.MyMap;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * My* = 本工程「自己写的」类（命名约定见 MsAiApplication）。
 *
 * <p>RAG 接口 —— 「检索增强生成」的最小可用实现。
 *
 * <p>================= 一次 RAG 到底干了什么 =================
 * <pre>
 *   ① offline：文档 → 切段 → EmbeddingModel 转向量 → 存进 VectorStore（见 MyKnowledgeLoader）
 *   ② online ：用户提问 → 同样过 EmbeddingModel 转成向量
 *   ③        → 在 VectorStore 里按余弦相似度召回 topK 条片段
 *   ④        → 把片段拼成 Prompt 上下文，连同问题一起丢给大模型
 *   ⑤        → 模型基于「你给的片段」作答，而不是凭记忆瞎编
 * </pre>
 *
 * <p>================= mode 两种，建议都试一遍 =================
 * <ul>
 *   <li><b>manual</b>（默认）：上面 ③④ 我们自己写 —— 拼 Prompt 的过程完全透明，
 *       你能亲眼看到「喂给模型的内容」是什么。学 RAG 原理必看。</li>
 *   <li><b>advisor</b>：交给 Spring AI 官方的 QuestionAnswerAdvisor 做，一行配置搞定，
 *       但中间过程是黑盒。适合学完 manual 之后看「框架替我省了什么」。</li>
 * </ul>
 *
 * <p>★ 构造器在哪儿？被 Lombok 的 {@code @RequiredArgsConstructor} 生成掉了：
 * 它会把下面两个 final 字段（vectorStores / chatClients）拼成一个构造器，
 * 等价于原来手写的 9 行 —— 这就是「样板代码交给工具」的典型收益。
 */
@RestController
@RequestMapping("/ai/rag")
@RequiredArgsConstructor
@Tag(name = "RAG 检索", description = "把笔记灌进向量库 → 对着笔记问答（开卷）。按学习顺序：reload 灌库 → add 加文档 → ask 问答 → stats 看统计。")
public class MyRagController {

    /** key = 通道名 + "VectorStore"，例如 dashscopeVectorStore */
    private final Map<String, VectorStore> vectorStores;

    /** key = 通道名 + "ChatClient"，例如 dashscopeChatClient */
    private final Map<String, ChatClient> chatClients;

    private final MyKnowledgeLoader knowledgeLoader;
    private final MyChunkService chunkService;

    @Value("${msai.rag.top-k:4}")
    private int defaultTopK;

    /**
     * 基于知识库问答。
     *
     * @param body provider=dashscope|openai|both，question=问题，topK=召回条数，mode=manual|advisor，store=memory|pg
     */
    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody RagRequest body) {
        var question = body.question();
        if (question == null || question.isBlank()) return MyMap.of("error", "question 不能为空");

        var provider = body.provider() == null || body.provider().isBlank() ? "dashscope" : body.provider();
        int topK = body.topK() == null || body.topK() <= 0 ? defaultTopK : body.topK();
        var mode = body.mode() == null ? "manual" : body.mode();
        // store=pg 用生产化 pgvector，其余（含缺省）用内存版 SimpleVectorStore —— 同页对比就靠这个参数
        var store = "pg".equalsIgnoreCase(body.store()) ? "pg" : "memory";

        // both：把所有通道各问一遍，并排看结果（同一 store 类型下不同 embedding 模型的召回质量差异一眼可见）
        if ("both".equalsIgnoreCase(provider)) {
            var providers = vectorStores.keySet().stream()
                    .filter(n -> n.endsWith(storeSuffix(store)))   // 只挑属于本 store 类型的 bean，避免 pg/内存 混着数
                    .map(n -> n.substring(0, n.length() - storeSuffix(store).length()))
                    .sorted().toList();
            var results = providers.stream()
                    .map(p -> answerOnce(p, question, topK, mode, store)).toList();
            return MyMap.of("mode", "both", "ragMode", mode, "store", store, "results", results);
        }
        return MyMap.of("mode", "single", "store", store, "result", answerOnce(provider, question, topK, mode, store));
    }

    /** store 类型对应的 VectorStore bean 后缀：memory=VectorStore，pg=VectorStorePg */
    private static String storeSuffix(String store) {
        return "pg".equals(store) ? "VectorStorePg" : "VectorStore";
    }

    /** 单通道的一次 RAG 问答 */
    private Map<String, Object> answerOnce(String provider, String question, int topK, String mode, String store) {
        long t0 = System.currentTimeMillis();
        try {
            var storeBean = vectorStores.get(provider + storeSuffix(store));
            var client = chatClients.get(provider + "ChatClient");
            if (storeBean == null || client == null) return MyMap.of("provider", provider, "ok", false,
                    "error", "通道 " + provider + " 不可用（VectorStore/ChatClient 缺失，检查 api-key 配置）");

            // ===== ③ 检索：把问题变成向量，召回最相似的 topK 条 =====
            long t1 = System.currentTimeMillis();
            var docs = storeBean.similaritySearch( // 执行向量相似度检索，召回最相关的文档片段
                    SearchRequest.builder().query(question).topK(topK).build()); // 构建检索请求：将问题转为向量，并指定召回条数 topK
            long searchMs = System.currentTimeMillis() - t1; // 计算并记录本次检索的耗时（毫秒）

            if (docs.isEmpty()) return MyMap.of("provider", provider, "ok", true, "sources", List.of(),
                    "answer", "知识库是空的，先调 POST /ai/rag/reload 灌库，或 POST /ai/rag/add 加一段文本。",
                    "elapsedMs", System.currentTimeMillis() - t0);

            // ===== ④ 生成：manual 自己拼 Prompt（透明）/ advisor 交给官方增强器（黑盒）=====
            var answer = "advisor".equalsIgnoreCase(mode) ?
                    askByAdvisor(client, storeBean, question, topK)
                    : askByPrompt(client, docs, question);

            return MyMap.of("provider", provider, "ok", true, "ragMode", mode, "store", store, "answer", answer,
                    "searchMs", searchMs,                                    // 检索耗时
                    "elapsedMs", System.currentTimeMillis() - t0,            // 总耗时（含检索与生成）
                    "recalled", docs.size(),                                 // 召回的片段数
                    // ★ 把命中的原文片段也返回 —— RAG 的可解释性就在这里：答案对不对，直接看它到底检索到了什么
                    "sources", docs.stream().map(this::preview).toList());
        } catch (Exception e) {
            return MyMap.of("provider", provider, "ok", false,
                    "elapsedMs", System.currentTimeMillis() - t0,
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 模式 B：官方封装，拼 prompt 的过程看不见 */
    private String askByAdvisor(ChatClient client, VectorStore store, String question, int topK) {
        var advisor = QuestionAnswerAdvisor.builder(store)
                .searchRequest(SearchRequest.builder().query(question).topK(topK).build()).build();
        return client.prompt()
                     .advisors(advisor)
                     .user(question).call().content();
    }

    /** 模式 A：自己拼 Prompt，过程完全透明 */
    private String askByPrompt(ChatClient client, List<Document> docs, String question) {
        return client.prompt()
                     .system(buildSystemPrompt(docs))
                     .user(question).call().content();
    }

    /** 检索命中片段 → 给前端展示的摘要（source / 第几段 / 前 200 字） */
    private Map<String, Object> preview(Document d) {
        var text = d.getText();
        return MyMap.of("source", d.getMetadata().get("source"),
                "chunkIndex", d.getMetadata().get("chunkIndex"),
                "preview", text.length() > 200 ? text.substring(0, 200) + "…" : text);
    }

    /**
     * 把检索到的片段拼成系统提示词。
     *
     * <p>★ 这是整个 RAG 里最值得你自己改的地方：换个措辞、加个「出处必须写到句尾」的要求、
     * 限制只用中文作答……效果会立刻不一样。这活儿叫 Prompt Engineering，调的就是这段字符串。
     */
    private String buildSystemPrompt(List<Document> docs) {
        var refs = docs.stream()
                .map(d -> "[" + d.getMetadata().get("source") + " #" + d.getMetadata().get("chunkIndex") + "]\n" + d.getText())
                .collect(Collectors.joining("\n\n"));
        return """
                你是基于【内部笔记】作答的助手，严格遵守以下规则：
                1. 只能依据下面提供的资料回答，不要使用资料之外的知识；
                2. 资料里没有的内容，直接回答「已知资料中没有提到」；
                3. 引用资料时用 [出处] 标注，出处是每个片段前面的方括号名称；
                4. 回答简洁，控制在 200 字以内。

                ===== 资料开始 =====
                """ + refs + "\n===== 资料结束 =====";
    }

    /** 知识库状态：灌了多少文件、多少片段、每个通道各多少、最近一次耗时 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        MyKnowledgeLoader.LoadStats st = knowledgeLoader.getLastStats();
        var out = st == null
                ? MyMap.of("loaded", false, "hint", "还没灌过库，调 POST /ai/rag/reload")
                : MyMap.of("loaded", true, "files", st.files(), "chunks", st.chunks(), "perStore", st.perStore(),
                           "elapsedMs", st.elapsedMs(), "lastReloadAt", st.at() == null ? null : st.at().toString(),
                           "errors", st.errors());
        out.put("availableProviders", vectorStores.keySet());
        out.put("loading", knowledgeLoader.isLoading());   // 灌库是异步的：true = 还在 embed，此时检索结果不完整
        out.put("now", LocalDateTime.now().toString());
        return out;
    }

    /** 手动重新灌库（改了 knowledge 目录里的文档后调这个） */
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        knowledgeLoader.reload();
        return stats();
    }

    /**
     * 临时加一段文本进知识库（不想上传文件时用它）。
     *
     * <p>★ 注意：SimpleVectorStore 是<b>内存</b>库，服务重启后这块数据会丢。
     * 真正持久化的内容请放进 external-dir 指向的目录，重启时会自动重新灌进去。
     */
    @PostMapping("/add")
    public Map<String, Object> add(@RequestBody AddRequest body) {
        if (body.text() == null || body.text().isBlank()) return MyMap.of("error", "text 不能为空");
        var source = body.source() == null || body.source().isBlank() ? "inline" : body.source();

        // 复用同一个切分器：切段规则和文件灌库保持一致
        var chunks = chunkService.split(body.text());
        var docs = java.util.stream.IntStream.range(0, chunks.size())
                .mapToObj(i -> new Document(source + "-" + i, chunks.get(i),
                        MyMap.of("app", "ms-ai", "source", source, "origin", "inline", "chunkIndex", i)))
                .toList();

        var out = MyMap.of("chunks", docs.size());
        vectorStores.forEach((name, store) -> {
            try {
                store.add(docs);
                out.put(name, "ok");
            } catch (Exception e) {
                out.put(name, "failed: " + e.getMessage());
            }
        });
        return out;
    }

    /** RAG 请求体 */
    public record RagRequest(String provider, String question, Integer topK, String mode, String store) {
    }

    /** 加文本请求体 */
    public record AddRequest(String source, String text) {
    }
}
