package com.example.ai.tools;

import com.example.ai.common.MyMap;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ★ Function Calling（工具调用）—— 让模型能调用我们自己的 Java 方法。
 * <p>
 * 原理一句话：Spring AI 把带 {@code @Tool} 注解的方法，连同名字 + 描述 + 参数 schema，
 * 在每次请求时一起发给模型。模型根据提问决定"要不要调、调哪个、传什么参"，然后由框架回调我们的方法，
 * 把返回值交还给模型，模型再据此组织最终回答。
 * <p>
 * 从易到难三个工具（对应学习台 tab 的预设提问）：
 *   add          → 简单无状态计算（模型直接算）
 *   now          → 返回当前时间（模型本来不知道"现在"）
 *   memoryCount  → 查 PG 里某个会话记住了多少条（模型本来碰不到你的数据库）
 * <p>
 * 每次工具被调用，都会记进当前线程的 {@code toolCalls}，controller 响应时 drain() 出来，
 * 用于前端展示"模型实际调了哪个工具、传了什么参、得了个什么结果"。
 */
@Component
public class ToolService {

    private static final ThreadLocal<List<Map<String, Object>>> toolCalls =
            ThreadLocal.withInitial(ArrayList::new);

    /** controller 调用后取出本次请求里模型实际发起的工具调用，并清理线程上下文。 */
    public static List<Map<String, Object>> drainCalls() {
        var list = toolCalls.get();
        toolCalls.remove();
        return list;
    }

    private final JdbcTemplate jdbc;

    public ToolService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private void record(String name, Object input, Object output) {
        toolCalls.get().add(MyMap.of("name", name, "input", String.valueOf(input), "output", String.valueOf(output)));
    }

    @Tool(name = "add", description = "计算两个整数的和。例如 add(2,3) 返回 5。用于用户要求做加法运算时。")
    public String add(int a, int b) {
        var r = String.valueOf(a + b);
        record("add", "a=" + a + ", b=" + b, r);
        return r;
    }

    @Tool(name = "now", description = "返回当前服务器时间（年月日时分秒）。用于回答任何关于'现在几点''今天星期几'的问题。")
    public String now() {
        var r = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE"));
        record("now", "-", r);
        return r;
    }

    @Tool(name = "memoryCount", description = "查询多轮对话中某个会话（sessionId/conversationId）当前累计记住了多少条消息。用于回答'某个会话聊了多少句''记忆有几条'。")
    public String memoryCount(String conversationId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_memory WHERE conversation_id = ?",
                Integer.class, conversationId);
        var r = "会话 " + conversationId + " 当前记住了 " + n + " 条消息";
        record("memoryCount", "conversationId=" + conversationId, r);
        return r;
    }
}
