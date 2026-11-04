package com.synapse.backend.services;

import com.synapse.backend.models.ChatMessage;
import com.synapse.backend.models.Conversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapse.backend.models.SqlResult;

@Service
@org.springframework.context.annotation.Profile({"conversation", "legacy"})
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final Duration CONVERSATION_CACHE_TTL = Duration.ofMinutes(5);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RedisCacheSupport cache;

    public ConversationService(JdbcTemplate jdbcTemplate, RedisCacheSupport cache) {
        this.jdbcTemplate = jdbcTemplate;
        this.cache = cache;
    }

    private String conversationCacheKey(UUID userId, UUID conversationId) {
        return "conv:" + userId + ":" + conversationId;
    }

    @Transactional
    public Conversation createConversation(String title, UUID userId, String userSub, String userEmail, String userName,
                                           String documentId, String documentName) {
        // Satisfy the PostgreSQL RLS Policy natively before attempting the INSERT
        setUserContext(userId);

        UUID conversationId = UUID.randomUUID();
        String finalTitle = (title != null && !title.isBlank()) ? title : "New Analysis";

        // Convert documentId to UUID safely for Postgres typing
        UUID docId = (documentId != null && !documentId.isBlank()) ? UUID.fromString(documentId) : null;

        jdbcTemplate.update(
                "INSERT INTO conversations (id, title, user_id, document_id, document_name, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                conversationId, finalTitle, userId, docId, documentName
        );

        createConversationSchema(conversationId, userId);

        return Conversation.builder()
                .id(conversationId.toString())
                .title(finalTitle)
                .userId(userId.toString())
                .documentId(documentId)
                .documentName(documentName)
                .messages(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Transactional(readOnly = true)
    public List<Conversation> getConversationsByUser(UUID userId) {
        if (userId == null) {
            log.warn("Refusing conversation lookup without an authenticated user subject");
            return new ArrayList<>();
        }
        setUserContext(userId);

        return jdbcTemplate.query(
                "SELECT * FROM conversations WHERE user_id = ? ORDER BY updated_at DESC",
                new ConversationRowMapper(),
                userId
        );
    }

    @Transactional
    public Optional<Conversation> getByIdAndUser(String id, UUID userId) {
        final UUID conversationId;
        try {
            conversationId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        String cacheKey = conversationCacheKey(userId, conversationId);
        Optional<Conversation> cached = cache.get(cacheKey, Conversation.class);
        if (cached.isPresent()) {
            return cached;
        }

        setUserContext(userId);
        try {
            Conversation conv = jdbcTemplate.queryForObject(
                    "SELECT * FROM conversations WHERE id = ? AND user_id = ?",
                    new ConversationRowMapper(),
                    conversationId, userId
            );

            if (conv != null) {
                createConversationSchema(conversationId, userId);
                conv.setMessages(loadChatMessages(conversationId));
                cache.put(cacheKey, conv, CONVERSATION_CACHE_TTL);
            }

            return Optional.ofNullable(conv);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Transactional
    public Conversation addMessage(String conversationId, UUID userId, ChatMessage message) {
        UUID convId = UUID.fromString(conversationId);
        String schemaName = "conv_" + conversationId.replace("-", "_");
        setUserContext(userId);

        Integer owned = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE id = ? AND user_id = ?",
                Integer.class, convId, userId);
        if (owned == null || owned == 0) {
            throw new IllegalArgumentException("Conversation not found for current user");
        }
        createConversationSchema(convId, userId);

        jdbcTemplate.update(
                "INSERT INTO \"" + schemaName + "\".chat_history (id, role, content, timestamp, sql_result) " +
                        "VALUES (?, ?, ?, ?, ?)",
                UUID.fromString(message.getId()), message.getRole(), message.getContent(),
                java.sql.Timestamp.from(message.getTimestamp()), serializeSqlResult(message.getSqlResult())
        );

        String title = message.getRole().equals("user")
                ? (message.getContent().length() > 28 ? message.getContent().substring(0, 28) + "..." : message.getContent())
                : null;
        if (title != null) {
            jdbcTemplate.update(
                    "UPDATE conversations SET title = CASE WHEN title = 'New Analysis' THEN ? ELSE title END, " +
                            "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?",
                    title, convId, userId);
        } else {
            jdbcTemplate.update("UPDATE conversations SET updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?",
                    convId, userId);
        }

        cache.evict(conversationCacheKey(userId, convId));
        return getByIdAndUser(conversationId, userId).orElse(null);
    }

    @Transactional
    public boolean attachDocument(String conversationId, UUID userId, String documentId, String documentName) {
        UUID convId = UUID.fromString(conversationId);
        setUserContext(userId);

        // Convert documentId to UUID safely for Postgres typing
        UUID docId = (documentId != null && !documentId.isBlank()) ? UUID.fromString(documentId) : null;

        jdbcTemplate.update(
                "UPDATE conversations SET document_id = ?, document_name = ?, title = ?, updated_at = CURRENT_TIMESTAMP " +
                "WHERE id = ? AND user_id = ?",
                docId, documentName, documentName, convId, userId
        );
        cache.evict(conversationCacheKey(userId, convId));
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM conversations WHERE id = ? AND user_id = ?",
                Integer.class, convId, userId) > 0;
    }

    @Transactional
    public boolean delete(String id, UUID userId) {
        UUID conversationId = UUID.fromString(id);
        String schemaName = "conv_" + id.replace("-", "_");
        setUserContext(userId);

        // Verify ownership before deleting
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE id = ? AND user_id = ?",
                Integer.class,
                conversationId, userId
        );

        if (count == null || count == 0) {
            log.warn("User {} attempted to delete conversation {} which they don't own", userId, id);
            return false;
        }

        jdbcTemplate.execute("DROP SCHEMA IF EXISTS \"" + schemaName + "\" CASCADE");

        int rows = jdbcTemplate.update("DELETE FROM conversations WHERE id = ? AND user_id = ?", conversationId, userId);
        cache.evict(conversationCacheKey(userId, conversationId));
        return rows > 0;
    }

    private void createConversationSchema(UUID conversationId, UUID userId) {
        String schemaName = "conv_" + conversationId.toString().replace("-", "_");

        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");

        // Ensure chat_history table exists (handles case where schema exists but table doesn't)
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS \"" + schemaName + "\".chat_history (" +
                        "id UUID PRIMARY KEY DEFAULT uuid_generate_v4(), " +
                        "role VARCHAR(20) NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"
        );
        jdbcTemplate.execute("ALTER TABLE \"" + schemaName + "\".chat_history ADD COLUMN IF NOT EXISTS sql_result TEXT");

        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS \"" + schemaName + "\".interactions (" +
                        "id UUID PRIMARY KEY DEFAULT uuid_generate_v4(), " +
                        "query_type VARCHAR(50), " +
                        "query_text TEXT, " +
                        "result TEXT, " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"
        );
    }

    private List<ChatMessage> loadChatMessages(UUID conversationId) {
        String schemaName = "conv_" + conversationId.toString().replace("-", "_");
        try {
            return jdbcTemplate.query(
                    "SELECT * FROM \"" + schemaName + "\".chat_history ORDER BY timestamp ASC",
                    (rs, rowNum) -> ChatMessage.builder()
                            .id(rs.getObject("id", UUID.class).toString())
                            .role(rs.getString("role"))
                            .content(rs.getString("content"))
                            .sqlResult(parseSqlResult(rs.getString("sql_result")))
                            .timestamp(rs.getTimestamp("timestamp").toInstant())
                            .build()
            );
        } catch (Exception e) {
            log.error("Failed to load messages for conversation {}", conversationId, e);
            return new ArrayList<>();
        }
    }

    private String serializeSqlResult(SqlResult result) {
        try { return result == null ? null : objectMapper.writeValueAsString(result); }
        catch (Exception e) { throw new IllegalStateException("Could not serialize SQL result", e); }
    }

    private SqlResult parseSqlResult(String value) {
        try { return value == null || value.isBlank() ? null : objectMapper.readValue(value, SqlResult.class); }
        catch (Exception e) { log.warn("Could not parse stored SQL result", e); return null; }
    }

    private void setUserContext(UUID userId) {
        jdbcTemplate.execute("SET LOCAL app.current_user_id = '" + userId + "'");
    }

    private static class ConversationRowMapper implements RowMapper<Conversation> {
        @Override
        public Conversation mapRow(ResultSet rs, int rowNum) throws SQLException {
            return Conversation.builder()
                    .id(rs.getObject("id", UUID.class).toString())
                    .title(rs.getString("title"))
                    .userId(rs.getObject("user_id", UUID.class).toString())
                    .documentId(rs.getString("document_id"))
                    .documentName(rs.getString("document_name"))
                    .messages(new ArrayList<>())
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .updatedAt(rs.getTimestamp("updated_at").toInstant())
                    .build();
        }
    }
}
