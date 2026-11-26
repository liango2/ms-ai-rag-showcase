-- =====================================================================
-- 多轮对话 · 生产化记忆底座（重启不丢）建表
-- 配合 ms-ai 的 /ai/chat/memory-pg 接口 + JdbcChatMemoryRepository
-- 用途：把 MessageChatMemoryAdvisor 的「历史存哪」从内存换成 PostgreSQL
-- 用法：psql -U msai -h 127.0.0.1 -d msai -f chat-memory.sql
-- =====================================================================

CREATE TABLE IF NOT EXISTS chat_memory (
    id             BIGSERIAL PRIMARY KEY,               -- 自增主键，保插入顺序
    conversation_id VARCHAR(128) NOT NULL,              -- 会话号 = chat_memory_conversation_id（隔离依据）
    sender_type    VARCHAR(32)  NOT NULL,               -- MessageType 枚举值：user / assistant / system
    content        TEXT         NOT NULL,               -- 这条消息的正文
    created_time   TIMESTAMP    NOT NULL DEFAULT now()  -- 取回时按此排序，还原对话顺序
);

CREATE INDEX IF NOT EXISTS idx_chat_memory_cid
    ON chat_memory (conversation_id, created_time);
