package com.example.ai.rag;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯 Java BM25 关键词检索器。
 *
 * <p>向量检索擅长语义近似，但对「pgvector」「MMFL」「第 7 条」这类精确词/数字/专有名词不敏感；
 * BM25 正好补这块。两路结果最后用 RRF 融合（见 {@link MyRrfFuser}）。
 *
 * <p>BM25 公式（经典三项）：
 * <pre>
 *   score(q,d) = Σ  IDF(t) · tf(t,d)·(k1+1) / ( tf(t,d) + k1·(1-b + b·|d|/avgdl) )
 *   IDF(t) = ln(1 + (N - df(t) + 0.5) / (df(t) + 0.5))
 * </pre>
 * k1=1.5 控制词频饱和（出现 10 次不会比 1 次强 10 倍），b=0.75 控制长度归一化（长文档不吃亏）。
 * 这里用带 +1 的平滑 IDF，避免罕见词 IDF 为负把分数拉翻。
 */
public final class MyBm25Retriever {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    /** 倒排索引：term → (docId → 词频) */
    private final Map<String, Map<String, Integer>> postings = new HashMap<>();
    private final Map<String, Document> docsById = new HashMap<>();
    private final Map<String, Integer> docLength = new HashMap<>();
    private final double avgDocLength;
    private final int docCount;

    /** 用一批文档建索引。文档来自 MyKnowledgeLoader 的内存快照，零外部依赖。 */
    public MyBm25Retriever(List<Document> docs) {
        this.docCount = docs.size();
        long totalLen = 0;
        for (Document d : docs) {
            var tokens = MyTokenizer.tokenize(d.getText());
            docsById.put(d.getId(), d);
            docLength.put(d.getId(), tokens.size());
            totalLen += tokens.size();
            for (String t : tokens) {
                postings.computeIfAbsent(t, k -> new HashMap<>())
                        .merge(d.getId(), 1, Integer::sum);
            }
        }
        this.avgDocLength = docCount == 0 ? 0 : (double) totalLen / docCount;
    }

    /**
     * 按 BM25 打分返回 topK 命中，名次写进 {@link MyRagHit#bm25Rank()}。
     * 分数暂存在 fusedScore 字段，交给 RRF 阶段覆盖（BM25 原始分只用于本路排序）。
     */
    public List<MyRagHit> search(String query, int topK) {
        var qTokens = MyTokenizer.tokenize(query);
        if (qTokens.isEmpty() || docCount == 0 || topK <= 0) return List.of();

        var scored = new ArrayList<MyRagHit>();
        for (var entry : docsById.entrySet()) {
            var id = entry.getKey();
            var tfMap = new HashMap<String, Integer>();
            for (String t : qTokens) tfMap.merge(t, 1, Integer::sum);

            double score = 0;
            int dl = docLength.getOrDefault(id, 0);
            for (var tfEntry : tfMap.entrySet()) {
                String term = tfEntry.getKey();
                int qTf = tfEntry.getValue();           // 查询里重复出现，按次数累加
                int df = postings.getOrDefault(term, Map.of()).size();
                if (df == 0) continue;
                double idf = Math.log(1 + (docCount - df + 0.5) / (df + 0.5));
                int tf = postings.get(term).getOrDefault(id, 0);
                if (tf == 0) continue;
                double norm = tf + K1 * (1 - B + B * dl / avgDocLength);
                score += idf * (tf * (K1 + 1) / norm) * qTf;
            }
            if (score > 0) {
                scored.add(new MyRagHit(entry.getValue(), 0, 0, score, 0));
            }
        }

        scored.sort(Comparator.comparingDouble(MyRagHit::fusedScore).reversed());
        var result = new ArrayList<MyRagHit>();
        int rank = 0;
        for (MyRagHit h : scored) {
            if (rank++ >= topK) break;
            result.add(new MyRagHit(h.doc(), 0, rank, h.fusedScore(), 0));
        }
        return result;
    }
}
