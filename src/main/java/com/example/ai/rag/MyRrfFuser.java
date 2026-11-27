package com.example.ai.rag;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）—— 把多路检索结果的排名合并成一份。
 *
 * <p>为什么不直接加 BM25 分和向量余弦分？两者量纲完全不同（一个 ~0-12，一个 ~0-1），
 * 加权求和得靠拍脑袋调权重，且换数据集就失效。RRF 只看「名次」，不看分数：
 * <pre>
 *   RRF(d) = Σ  1 / (k + rank_i(d))      两路都没召回的文档不参与
 * </pre>
 * k=60 是文献里的经验值：它压平了头名优势，让「两路都排第 5」的文档能赢过「一路第 1、另一路没召回」。
 * 好处是零调参、对量纲免疫 —— 正好符合本项目「可解释 > 玄学」的取向。
 */
public final class MyRrfFuser {

    /** 平滑常数，抑制单路冠军的压倒性优势 */
    public static final int DEFAULT_K = 60;

    private final int k;

    public MyRrfFuser() { this(DEFAULT_K); }

    public MyRrfFuser(int k) {
        this.k = k;
    }

    /**
     * 融合向量路与 BM25 路的命中列表，返回按 RRF 分降序的 topK。
     *
     * @param vectorHits 向量召回（vectorRank 已填）
     * @param bm25Hits   BM25 召回（bm25Rank 已填）
     * @param topK       最终返回条数
     */
    public List<MyRagHit> fuse(List<MyRagHit> vectorHits, List<MyRagHit> bm25Hits, int topK) {
        // 按 id 汇总：一个片段被两路都召回时，分数相加（这是 RRF 的精髓）
        var merged = new LinkedHashMap<String, MyRagHit>();

        for (MyRagHit h : vectorHits) {
            var cur = merged.get(h.id());
            if (cur == null) {
                merged.put(h.id(), new MyRagHit(h.doc(), h.vectorRank(), 0,
                        1.0 / (k + h.vectorRank()), 0));
            } else {
                merged.put(h.id(), new MyRagHit(cur.doc(), h.vectorRank(), cur.bm25Rank(),
                        cur.fusedScore() + 1.0 / (k + h.vectorRank()), 0));
            }
        }
        for (MyRagHit h : bm25Hits) {
            var cur = merged.get(h.id());
            if (cur == null) {
                merged.put(h.id(), new MyRagHit(h.doc(), 0, h.bm25Rank(),
                        1.0 / (k + h.bm25Rank()), 0));
            } else {
                merged.put(h.id(), new MyRagHit(cur.doc(), cur.vectorRank(), h.bm25Rank(),
                        cur.fusedScore() + 1.0 / (k + h.bm25Rank()), 0));
            }
        }

        var list = new ArrayList<>(merged.values());
        list.sort(Comparator.comparingDouble(MyRagHit::fusedScore).reversed());
        if (topK >= list.size()) return list;
        return new ArrayList<>(list.subList(0, topK));
    }

    /** 供单测/诊断：只关心一批文档时按 id 找 rank（1 起）。 */
    public static int rankOf(List<MyRagHit> hits, String id) {
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).id().equals(id)) return i + 1;
        }
        return 0;
    }
}
