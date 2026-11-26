package com.example.ai.chat.memory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 生产化记忆底座：把 Message 落进 PostgreSQL（表 chat_memory），重启不丢。
 * <p>
 * 它替换 Spring AI 默认的内存版 {@code InMemoryChatMemoryRepository}：
 * 同一个 {@code MessageWindowChatMemory}，只是把「记忆存哪」从内存换成 PG，滑动窗口 / 取回填行为不变。
 * 与 RAG 的演进同构：内存 SimpleVectorStore → PgVectorStore；内存 ChatMemory → 这里。
 */
public class JdbcChatMemoryRepository implements ChatMemoryRepository {

    private final JdbcTemplate jdbc;

    public JdbcChatMemoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 行 → Message。tool 等类型不进记忆窗口（多轮记忆里只会出现 user / assistant / system）。 */
    private static final RowMapper<Message> ROW = (rs, i) -> {
        var text = rs.getString("content");
        return switch (MessageType.fromValue(rs.getString("sender_type"))) {
            case USER -> new UserMessage(text);
            case ASSISTANT -> new AssistantMessage(text);
            case SYSTEM -> new SystemMessage(text);
            default -> null;
        };
    };

    @Override
    public List<String> findConversationIds() {
        return jdbc.queryForList(
                "SELECT DISTINCT conversation_id FROM chat_memory ORDER BY conversation_id",
                String.class);
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        var rows = jdbc.query(
                "SELECT sender_type, content FROM chat_memory WHERE conversation_id = ? ORDER BY created_time, id",
                ROW, conversationId);
        var out = new ArrayList<Message>(rows.size());
        for (var m : rows) if (m != null) out.add(m);
        return out;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        for (var m : messages) {
            jdbc.update(
                    "INSERT INTO chat_memory(conversation_id, sender_type, content) VALUES (?,?,?)",
                    conversationId, m.getMessageType().getValue(), m.getText());
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        jdbc.update("DELETE FROM chat_memory WHERE conversation_id = ?", conversationId);
    }
}
