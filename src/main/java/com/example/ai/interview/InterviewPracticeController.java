package com.example.ai.interview;

import com.example.ai.common.MyMap;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * ★ 面试练习系统：出题 → 我的回答 → 与标准答案双栏对比（两栏都可编辑）。
 * <p>
 * 设计要点（对齐用户「模拟面试 > 背卡片」的复习策略）：
 * <pre>
 *   1  建表 + 种子：启动时 CREATE TABLE IF NOT EXISTS；表空则自动灌入
 *      InterviewSeed 里的 3 份卷子（擎天/天马/兔展）与 STAR 闪卡卡组；
 *   2  出题：GET /ai/interview/papers（卷列表+进度）→ GET /ai/interview/questions?company=xx；
 *   3  作答：PUT /ai/interview/answer/{id} 只存我的回答；
 *   4  对比：PUT /ai/interview/answer-both/{id} 我的+标准两栏都可编辑保存；
 *   5  闪卡进度：GET /ai/interview/flashcards + PUT /ai/interview/flashcard/{cardKey}。
 * </pre>
 * 存储用 JdbcTemplate（和 TokenController / JdbcChatMemoryRepository 同款），
 * 不引 ORM —— 本项目一贯风格：够用、透明、教学可查。
 */
@RestController
@RequestMapping("/ai/interview")
@RequiredArgsConstructor
@Tag(name = "面试练习", description = "3 家公司 3 份卷子（擎天AI工程化/天马电商后端/兔展AI平台）：①papers 看卷进度 → ②questions 出题 → ③answer 存我的回答 → ④answer-both 双栏对比编辑 → ⑤flashcards 闪卡学习进度。")
public class InterviewPracticeController {

    private final JdbcTemplate jdbc;

    /** 启动后：建表（幂等）+ 空表灌种子（幂等，ON CONFLICT 防重复） */
    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS interview_practice (
                    id            BIGSERIAL PRIMARY KEY,
                    company       TEXT        NOT NULL,
                    company_name  TEXT        NOT NULL,
                    topic         TEXT        NOT NULL,
                    question      TEXT        NOT NULL,
                    user_answer   TEXT        NOT NULL DEFAULT '',
                    model_answer  TEXT        NOT NULL DEFAULT '',
                    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ip_company ON interview_practice (company)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS flashcard_progress (
                    id             BIGSERIAL PRIMARY KEY,
                    card_key       TEXT        NOT NULL UNIQUE,
                    card_title     TEXT        NOT NULL,
                    status         TEXT        NOT NULL DEFAULT 'new',
                    review_count   INT         NOT NULL DEFAULT 0,
                    last_review_at TIMESTAMPTZ,
                    note           TEXT        NOT NULL DEFAULT '',
                    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
                )""");

        Long n = jdbc.queryForObject("SELECT count(*) FROM interview_practice", Long.class);
        if (n == null || n == 0) {
            for (var q : InterviewSeed.questions()) {
                jdbc.update("""
                        INSERT INTO interview_practice (company, company_name, topic, question, model_answer)
                        VALUES (?, ?, ?, ?, ?)
                        """, q.company(), q.companyName(), q.topic(), q.question(), q.modelAnswer());
            }
        }
        for (var c : InterviewSeed.flashcards()) {
            jdbc.update("""
                    INSERT INTO flashcard_progress (card_key, card_title, note)
                    VALUES (?, ?, ?)
                    ON CONFLICT (card_key) DO NOTHING
                    """, c.cardKey(), c.cardTitle(), c.note());
        }
    }

    /** ① 卷列表 + 每卷进度：总题数 / 已作答数（用于前端下拉 + 首页概览） */
    @GetMapping("/papers")
    @Operation(summary = "1 · 卷列表与进度", description = "返回 3 份卷子（擎天/天马/兔展）：每题总数、已作答数、上次更新时间。")
    public Map<String, Object> papers() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT company, company_name,
                       count(*)                 AS total,
                       count(*) FILTER (WHERE user_answer <> '') AS answered,
                       max(updated_at)          AS last_updated
                FROM interview_practice
                GROUP BY company, company_name
                ORDER BY company
                """);
        return MyMap.of("ok", true, "papers", rows);
    }

    /** ② 出题：按卷取全部题目（含我的回答与标准答案，前端决定是否展开） */
    @GetMapping("/questions")
    @Operation(summary = "2 · 按卷出题", description = "company=qingting|tianma|tuzhan，返回该卷全部题目：考点/题目/我的回答/标准答案/更新时间。")
    public Map<String, Object> questions(@RequestParam String company) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, topic, question, user_answer, model_answer, updated_at
                FROM interview_practice
                WHERE company = ?
                ORDER BY id
                """, company);
        return MyMap.of("ok", true, "company", company, "count", rows.size(), "questions", rows);
    }

    /** ③ 存我的回答（左栏）：答案区收起时快速保存口述/打字稿 */
    @PutMapping("/answer/{id}")
    @Operation(summary = "3 · 保存我的回答", description = "只更新 user_answer 字段（左栏）；body: {\"userAnswer\": \"...\"}。")
    public Map<String, Object> saveAnswer(@PathVariable long id, @RequestBody Map<String, String> body) {
        String ua = body.getOrDefault("userAnswer", "");
        int n = jdbc.update("UPDATE interview_practice SET user_answer = ?, updated_at = now() WHERE id = ?", ua, id);
        return MyMap.of("ok", n > 0, "id", id, "userAnswerSaved", n > 0);
    }

    /** ④ 双栏对比：我的回答 + 标准答案两栏都可编辑（改标准答案 = 沉淀自己的参考答案） */
    @PutMapping("/answer-both/{id}")
    @Operation(summary = "4 · 双栏对比保存", description = "body: {\"userAnswer\": \"...\", \"modelAnswer\": \"...\"}——两栏都可编辑，保存后下次打开仍是新值。")
    public Map<String, Object> saveBoth(@PathVariable long id, @RequestBody Map<String, String> body) {
        String ua = body.getOrDefault("userAnswer", "");
        String ma = body.getOrDefault("modelAnswer", "");
        int n = jdbc.update("""
                UPDATE interview_practice
                SET user_answer = ?, model_answer = ?, updated_at = now()
                WHERE id = ?
                """, ua, ma, id);
        return MyMap.of("ok", n > 0, "id", id, "saved", n > 0);
    }

    /** ⑤ 闪卡进度列表 + 汇总：总卡 / 各状态数量（用于顶部进度条） */
    @GetMapping("/flashcards")
    @Operation(summary = "5 · 闪卡学习进度", description = "STAR 卡组每张卡的复习状态：new/learning/reviewing/mastered、复习次数、上次复习时间；附汇总。")
    public Map<String, Object> flashcards() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, card_key, card_title, status, review_count, last_review_at, note, updated_at
                FROM flashcard_progress
                ORDER BY id
                """);
        Map<String, Object> summary = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'mastered') AS mastered,
                       count(*) FILTER (WHERE status IN ('learning','reviewing')) AS in_progress,
                       count(*) FILTER (WHERE status = 'new') AS fresh
                FROM flashcard_progress
                """);
        return MyMap.of("ok", true, "cards", rows, "summary", summary);
    }

    /** ⑥ 更新某张闪卡进度：review=true → 复习次数+1、上次复习=now；status 可传 new/learning/reviewing/mastered */
    @PutMapping("/flashcard/{cardKey}")
    @Operation(summary = "6 · 闪卡复习打卡", description = "body: {\"review\": true, \"status\": \"mastered\"}——review 打卡一次（次数+1），status 可选改状态；两者可单独传。")
    public Map<String, Object> touchFlashcard(@PathVariable String cardKey, @RequestBody Map<String, String> body) {
        boolean review = Boolean.parseBoolean(body.getOrDefault("review", "false"));
        String status = body.get("status");
        if (review) {
            jdbc.update("""
                    UPDATE flashcard_progress
                    SET review_count = review_count + 1, last_review_at = now(), updated_at = now()
                    WHERE card_key = ?
                    """, cardKey);
        }
        if (status != null && !status.isBlank()) {
            jdbc.update("UPDATE flashcard_progress SET status = ?, updated_at = now() WHERE card_key = ?", status, cardKey);
        }
        return MyMap.of("ok", true, "cardKey", cardKey);
    }
}
