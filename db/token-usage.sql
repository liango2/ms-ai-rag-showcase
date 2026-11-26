-- =====================================================================
-- Token 记账 · 每次模型调用的 token 用量落库（聊天/流式共用一张表）
-- 配合 ms-ai 的 TokenUsageRecorder（写）+ /ai/token/*（查）
-- 用途：把"每次调用的 token 数"从一次性响应变成可查的历史账本
--       面试口述稿里的"跑 3 次填数字"就查这张表/记账页
-- 用法：psql -U msai -h 127.0.0.1 -d msai -f token-usage.sql
-- =====================================================================

CREATE TABLE IF NOT EXISTS token_usage (
    id                BIGSERIAL PRIMARY KEY,          -- 自增主键
    endpoint          VARCHAR(32)  NOT NULL,          -- 哪个接口记的账：chat / stream
    provider          VARCHAR(32)  NOT NULL,          -- 通道：dashscope / openai / siliconflow
    model             VARCHAR(64),                    -- 实际模型名（如 qwen-plus / Qwen2.5-7B-Instruct）
    prompt_tokens     INT          NOT NULL DEFAULT 0, -- 输入消耗（提示词+system+历史）
    completion_tokens INT          NOT NULL DEFAULT 0, -- 输出消耗（模型生成部分）
    total_tokens      INT          NOT NULL DEFAULT 0, -- 总消耗 = 前两者之和（计费依据）
    elapsed_ms        BIGINT,                          -- 本次调用耗时
    created_time      TIMESTAMP    NOT NULL DEFAULT now() -- 记账时间（按日统计用）
);

CREATE INDEX IF NOT EXISTS idx_token_usage_time
    ON token_usage (created_time);

CREATE INDEX IF NOT EXISTS idx_token_usage_endpoint
    ON token_usage (endpoint, created_time);
