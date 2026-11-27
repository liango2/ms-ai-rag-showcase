package com.example.ai.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 轻量可解释 rerank（词汇覆盖 + 位置加权）—— 融合之后再精排一遍。
 *
 * <p>和「调用 cross-encoder 模型」的重排相比，这个实现零模型依赖、分数能一眼看懂，
 * 适合作为教学项目里的「rerank 是什么」的最小可运行样例。真实项目里把 rerank() 内部
 * 换成 cross-encoder 调用即可，外层接口（吃 MyRagHit 列表、吐带分数的列表）不用动。
 *
 * <p>打分 = 覆盖率(0-1) × 覆盖率权重 + 紧密度(0-1) × 位置权重
 * <ul>
 *   <li>覆盖率：查询 token 有多少比例出现在片段里 —— 覆盖越多越相关；</li>
 *   <li>紧密度：命中 token 在片段里出现得越靠前越好（摘要/标题常常在开头）；</li>
 *   <li>做 rerank 时把得分写进 rerankScore，fusedScore 保留不动，便于前端对比两阶段顺序。</li>
 * </ul>
 */
public final class MyReranker {

    private static final double COVERAGE_WEIGHT = 0.75;
    private static final double PROXIMITY_WEIGHT = 0.25;

    /** 按 rerank 分重排；输入列表顺序不变（返回新列表）。 */
    public List<MyRagHit> rerank(String query, List<MyRagHit> hits) {
        if (hits == null || hits.isEmpty() || query == null || query.isBlank()) {
            return hits == null ? List.of() : new ArrayList<>(hits);
        }

        var qTokens = MyTokenizer.tokenize(query);
        if (qTokens.isEmpty()) return new ArrayList<>(hits);

        var out = new ArrayList<MyRagHit>();
        for (MyRagHit h : hits) {
            var textTokens = MyTokenizer.tokenize(h.doc().getText());
            var textSet = new HashSet<>(textTokens);
            Set<String> qSet = new HashSet<>(qTokens);

            int covered = 0;
            int firstHitPos = Integer.MAX_VALUE;
            for (String t : qSet) {
                if (textSet.contains(t)) {
                    covered++;
                    int pos = textTokens.indexOf(t);
                    if (pos >= 0 && pos < firstHitPos) firstHitPos = pos;
                }
            }
            double coverage = (double) covered / qSet.size();
            double proximity = covered == 0 || firstHitPos == Integer.MAX_VALUE
                    ? 0 : 1.0 / (1 + firstHitPos);

            double score = coverage * COVERAGE_WEIGHT + proximity * PROXIMITY_WEIGHT;
            out.add(new MyRagHit(h.doc(), h.vectorRank(), h.bm25Rank(), h.fusedScore(), score));
        }

        // ★ 坑：Comparator.reversed() 作用于【整条链】，不能链式写两个。
        //   正确写法是主比较器 reversed 后，把次比较器单独包一层 reversed 再 thenComparing。
        out.sort(Comparator
                .comparingDouble(MyRagHit::rerankScore).reversed()
                .thenComparing(Comparator.comparingDouble(MyRagHit::fusedScore).reversed()));
        return out;
    }
}
