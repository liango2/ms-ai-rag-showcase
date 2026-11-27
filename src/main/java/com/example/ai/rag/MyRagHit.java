package com.example.ai.rag;

import org.springframework.ai.document.Document;

/**
 * 检索命中的统一载体：一个片段 + 它在各路的名次/得分。
 *
 * <p>向量检索、BM25、RRF 融合、rerank 四步都往同一个对象上填字段，
 * 最后 controller 直接把它摊成返回 JSON —— 诊断信息就是这么来的，不用到处传 Map。
 *
 * @param doc         命中的文档片段
 * @param vectorRank  向量检索里的名次（1 起；0 = 该路没召回）
 * @param bm25Rank    BM25 检索里的名次（1 起；0 = 该路没召回）
 * @param fusedScore  RRF 融合得分
 * @param rerankScore rerank 后的得分（0 = 未做 rerank）
 */
public record MyRagHit(Document doc, int vectorRank, int bm25Rank, double fusedScore, double rerankScore) {

    public String id() {
        return doc.getId();
    }
}
