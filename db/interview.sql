-- ============================================================================
-- ms-ai · 面试练习系统 建表 SQL（参考用；应用启动时会自动 CREATE IF NOT EXISTS + 空表 seed）
-- ============================================================================
-- 面试练习：出题 → 我的回答 → 与标准答案双栏对比（两栏都可编辑）
-- 表名: interview_practice   卷(company): qingting擎天 / tianma天马 / tuzhan兔展
CREATE TABLE IF NOT EXISTS interview_practice (
    id            BIGSERIAL PRIMARY KEY,
    company       TEXT        NOT NULL,                -- 卷标识: qingting / tianma / tuzhan
    company_name  TEXT        NOT NULL,                -- 卷中文名: 擎天科技 / 天马集团 / 兔展
    topic         TEXT        NOT NULL,                -- 考点/主题（如: AI工程化 / JVM / 微服务）
    question      TEXT        NOT NULL,                -- 面试题
    user_answer   TEXT        NOT NULL DEFAULT '',     -- 我的回答（左栏，可编辑）
    model_answer  TEXT        NOT NULL DEFAULT '',     -- 标准答案（右栏，可编辑）
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ip_company ON interview_practice (company);

-- 闪卡学习进度：STAR 话术卡组每张卡一条进度（复习几次/状态/上次复习时间）
CREATE TABLE IF NOT EXISTS flashcard_progress (
    id             BIGSERIAL PRIMARY KEY,
    card_key       TEXT        NOT NULL UNIQUE,        -- 卡标识: star-open / star-s / star-p ...
    card_title     TEXT        NOT NULL,               -- 卡标题（面试页面显示）
    status         TEXT        NOT NULL DEFAULT 'new', -- new 新卡 / learning 学习中 / reviewing 复习中 / mastered 已掌握
    review_count   INT         NOT NULL DEFAULT 0,     -- 累计复习次数
    last_review_at TIMESTAMPTZ,                        -- 上次复习时间
    note           TEXT        NOT NULL DEFAULT '',    -- 记忆点备注（可编辑）
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
