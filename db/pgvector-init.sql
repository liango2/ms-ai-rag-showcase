-- ============================================================================
-- ms-ai RAG 生产化向量库：pgvector 初始化脚本
--   PostgreSQL 16.15 + pgvector 0.8.0（OpenCloudOS 9.6 / 2C2G 轻量服务器）
--   Spring AI 1.1.8 的 PgVectorStore 默认 initializeSchema=true 会自动建表，
--   这份脚本的价值在：① 服务端一次性初始化 ② 手工建表 + 索引策略对比实验。
--   对应教学页：ch22 生产化向量库 · pgvector
-- ============================================================================

-- ----------------------------------------------------------------------------
-- ① 服务端一次性初始化：建用户、建库、启用扩展（在 postgres 超级用户下执行）
-- ----------------------------------------------------------------------------
CREATE ROLE msai LOGIN PASSWORD 'msai123';
CREATE DATABASE msai OWNER msai;

-- 以下在 msai 库内执行（psql -U msai -d msai）：
CREATE EXTENSION IF NOT EXISTS vector;

-- ----------------------------------------------------------------------------
-- ② 建表（与 Spring AI PgVectorStore 默认结构一致）
--    id        : 文档主键（uuid，Spring AI 默认 PgIdType.UUID）
--    content   : 原文片段
--    metadata  : 元数据 JSON（存来源文件名、页码、chunk 序号等）
--    embedding : 向量列，维度必须和 embedding 模型一致：
--                DashScope text-embedding-v3 默认 1024 维
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vector_store (
    id        varchar PRIMARY KEY,   -- Spring AI PgIdType.STRING：我们的文档 id 是 "ms-ai-1" 这种字符串
    content   text,
    metadata  json,
    embedding vector(1024)
);

-- ----------------------------------------------------------------------------
-- ③ 索引策略对比实验（同一张表只能挂一种索引，对比用三张表或重建索引）
--    cosine 相似度（Spring AI 里 PgDistanceType.COSINE_DISTANCE）
-- ----------------------------------------------------------------------------

-- 策略 A：精确搜索（NONE / 不建索引）
--   底层是顺序扫描 + 全量算余弦距离。数据量小（几百条）时最快最准，召回 100%；
--   数据量上万后线性退化。适合当「正确性基准线」。

-- 策略 B：IVFFlat（PgIndexType.IVFFLAT）
--   先把向量粗聚类成 lists 个桶，查询只扫命中的桶。
--   ★ 必须先灌完数据再建索引（它按数据分布分桶）；lists 一般取行数的 1/1000。
CREATE INDEX IF NOT EXISTS idx_vector_store_ivfflat
    ON vector_store USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- 策略 C：HNSW（PgIndexType.HNSW）
--   建索引时就把向量连成一张「跳表」式的近似图，查询沿着图走。
--   建索引比 IVFFlat 慢一点、占内存多一点，但查询快且召回高，小数据量也稳定。
--   m = 每个节点的最大连接数（默认 16），ef_search = 查询时探索范围。
CREATE INDEX IF NOT EXISTS idx_vector_store_hnsw
    ON vector_store USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

-- ----------------------------------------------------------------------------
-- 附：日常排查 SQL
-- ----------------------------------------------------------------------------
-- 看向量表里有多少条（reload 后应该和知识库片段数一致）：
--   SELECT count(*) FROM vector_store;
-- 手工查相似度（topK=3 最近邻，越小越相似）：
--   SELECT content, embedding <=> '{...1024 个浮点数...}' AS distance FROM vector_store
--   ORDER BY embedding <=> '{...}' LIMIT 3;
-- 删光（等价于 reload 里的 clearStore）：
--   DELETE FROM vector_store WHERE metadata->>'app' = 'ms-ai';
