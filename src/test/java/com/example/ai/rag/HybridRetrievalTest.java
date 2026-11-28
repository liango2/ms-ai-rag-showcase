package com.example.ai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 混合检索纯逻辑单测 —— 不依赖向量库、模型、数据库，mvn test 秒过。
 * 覆盖 issue #4 验收标准里的：分词、BM25 排序、RRF 融合去重、rerank 重排、空输入。
 */
class HybridRetrievalTest {

    private static Document doc(String id, String text) {
        return new Document(id, text, Map.of("source", id + ".md", "chunkIndex", 0));
    }

    // ---------- ① 分词 ----------

    @Test
    void tokenizerSplitsChineseBigramAndKeepsEnglishWord() {
        var tokens = MyTokenizer.tokenize("向量检索 pgvector 和 BM25");
        assertTrue(tokens.contains("向量"), "中文 bigram");
        assertTrue(tokens.contains("检索"), "中文 bigram");
        assertTrue(tokens.contains("pgvector"), "英文整词");
        assertTrue(tokens.contains("bm25"), "英文转小写");
        assertFalse(tokens.contains(" "), "空白被丢弃");
    }

    @Test
    void tokenizerHandlesEmptyAndSingleChar() {
        assertTrue(MyTokenizer.tokenize(null).isEmpty());
        assertTrue(MyTokenizer.tokenize("  ").isEmpty());
        assertTrue(MyTokenizer.tokenize("中").isEmpty(), "单字不成 bigram，返回空");
    }

    // ---------- ② BM25 ----------

    @Test
    void bm25RanksDocWithQueryTermsOnTop() {
        var docs = List.of(
                doc("a", "今天天气不错适合出去散步"),
                doc("b", "混合检索把向量检索和关键词检索结合起来"),
                doc("c", "数据库配置在 application.yml 里"));
        var hits = new MyBm25Retriever(docs).search("向量检索", 3);

        assertFalse(hits.isEmpty());
        assertEquals("b", hits.get(0).id(), "含两个查询词的文档应排第一");
        assertEquals(1, hits.get(0).bm25Rank(), "名次从 1 起");
        assertTrue(hits.stream().noneMatch(h -> h.id().equals("c")), "无关文档不应召回");
    }

    @Test
    void bm25EmptyInputsReturnEmpty() {
        var retriever = new MyBm25Retriever(List.of(doc("a", "向量检索")));
        assertTrue(retriever.search("", 5).isEmpty());
        assertTrue(retriever.search("向量", 0).isEmpty());
        assertTrue(new MyBm25Retriever(List.<Document>of()).search("向量", 5).isEmpty());
    }

    // ---------- ③ RRF 融合 ----------

    @Test
    void rrfRewardsDocFoundByBothRoutesAndDeduplicates() {
        var dA = doc("a", "混合检索");
        var dB = doc("b", "向量");
        var dC = doc("c", "关键词");

        // a 两路都召回（第2、第2），b 只在向量第1，c 只在 BM25 第1
        var vec = List.of(new MyRagHit(dA, 2, 0, 0, 0), new MyRagHit(dB, 1, 0, 0, 0));
        var bm25 = List.of(new MyRagHit(dA, 0, 2, 0, 0), new MyRagHit(dC, 0, 1, 0, 0));

        var fused = new MyRrfFuser().fuse(vec, bm25, 10);

        assertEquals(3, fused.size(), "三篇文档各出现一次（去重）");
        assertEquals("a", fused.get(0).id(), "两路都命中的 a 分数最高");
        assertEquals(2, fused.get(0).vectorRank());
        assertEquals(2, fused.get(0).bm25Rank());
        double expected = 2.0 / (60 + 2);   // 1/(60+2) + 1/(60+2)
        assertEquals(expected, fused.get(0).fusedScore(), 1e-9);
    }

    @Test
    void rrfRespectsTopKAndEmptyInputs() {
        var d = doc("a", "x");
        var vec = List.of(new MyRagHit(d, 1, 0, 0, 0));
        var bm25 = List.of(new MyRagHit(doc("b", "y"), 0, 1, 0, 0));

        assertEquals(1, new MyRrfFuser().fuse(vec, bm25, 1).size(), "topK 生效");
        assertTrue(new MyRrfFuser().fuse(List.of(), List.of(), 5).isEmpty());
    }

    // ---------- ④ Rerank ----------

    @Test
    void rerankPutsHigherQueryCoverageFirstAndKeepsFusedScore() {
        var low = new MyRagHit(doc("low", "完全无关的一段文字"), 1, 0, 0.031, 0);
        var high = new MyRagHit(doc("high", "向量检索和混合检索的召回融合"), 2, 0, 0.020, 0);

        var out = new MyReranker().rerank("向量检索", List.of(low, high));

        assertEquals("high", out.get(0).id(), "覆盖查询词更多的排到前面");
        assertEquals(0.020, out.get(0).fusedScore(), 1e-9, "融合分保留不动");
        assertTrue(out.get(0).rerankScore() > out.get(1).rerankScore());
    }

    @Test
    void rerankHandlesEmptyAndDoesNotMutateInput() {
        var a = new MyRagHit(doc("a", "向量检索"), 1, 0, 0.03, 0);
        var b = new MyRagHit(doc("b", "无"), 2, 0, 0.02, 0);
        var input = List.of(b, a);

        var out = new MyReranker().rerank("向量检索", input);

        assertEquals("b", input.get(0).id(), "原列表顺序不变");
        assertTrue(new MyReranker().rerank("", input).size() == 2);
        assertTrue(new MyReranker().rerank("x", List.of()).isEmpty());
    }
}
