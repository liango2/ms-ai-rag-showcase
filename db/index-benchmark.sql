-- 三索引策略实测：1万条 1024 维随机向量（本地实验，不调外部 API）
DROP TABLE IF EXISTS vs_exp;
CREATE TABLE vs_exp (id text PRIMARY KEY, embedding vector(1024));
INSERT INTO vs_exp
SELECT 'd' || g, (SELECT array_agg(random() * 2 - 1)::vector FROM generate_series(1, 1024))
FROM generate_series(1, 10000) AS g;
ANALYZE vs_exp;

-- ① 精确扫描（无索引，seqscan 默认）
EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM vs_exp ORDER BY embedding <=> (SELECT embedding FROM vs_exp WHERE id = 'd5000') LIMIT 5;

-- ② IVFFlat（lists=100，必须先有数据才能建）
CREATE INDEX vs_exp_ivf ON vs_exp USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
SET enable_seqscan = off;
EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM vs_exp ORDER BY embedding <=> (SELECT embedding FROM vs_exp WHERE id = 'd5000') LIMIT 5;

-- ③ HNSW（空表即可建，沿图搜索）
DROP INDEX vs_exp_ivf;
CREATE INDEX vs_exp_hnsw ON vs_exp USING hnsw (embedding vector_cosine_ops);
EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM vs_exp ORDER BY embedding <=> (SELECT embedding FROM vs_exp WHERE id = 'd5000') LIMIT 5;
