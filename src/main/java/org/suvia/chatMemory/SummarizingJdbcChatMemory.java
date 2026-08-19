package org.suvia.chatMemory;

import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL conversation memory with optimistic version checks. */
public class SummarizingJdbcChatMemory extends AbstractSummarizingChatMemory {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS suvia_chat_memory (
                conversation_id VARCHAR(128) PRIMARY KEY,
                state BYTEA NOT NULL,
                version BIGINT NOT NULL DEFAULT 0,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
    private static final String ADD_VERSION = """
            ALTER TABLE suvia_chat_memory
            ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0
            """;
    private static final String SELECT = """
            SELECT state, version FROM suvia_chat_memory WHERE conversation_id = ?
            """;
    private static final String INSERT = """
            INSERT INTO suvia_chat_memory (conversation_id, state, version, updated_at)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (conversation_id) DO NOTHING
            """;
    private static final String UPDATE = """
            UPDATE suvia_chat_memory
            SET state = ?, version = ?, updated_at = CURRENT_TIMESTAMP
            WHERE conversation_id = ? AND version = ?
            """;
    private static final String DELETE = "DELETE FROM suvia_chat_memory WHERE conversation_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public SummarizingJdbcChatMemory(
            JdbcTemplate jdbcTemplate,
            ConversationSummarizer summarizer,
            int maxRecentMessages,
            int maxRecentTokens,
            int summarizeBatchSize,
            int minRecentMessages,
            int maxSummaryCharacters
    ) {
        super(
                summarizer,
                maxRecentMessages,
                maxRecentTokens,
                summarizeBatchSize,
                minRecentMessages,
                maxSummaryCharacters
        );
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcTemplate.execute(CREATE_TABLE);
        this.jdbcTemplate.execute(ADD_VERSION);
    }

    @Override
    protected ConversationState load(String conversationId) {
        ConversationState state = jdbcTemplate.query(SELECT, resultSet -> {
            if (!resultSet.next()) {
                return null;
            }
            ConversationState loaded = deserializeState(resultSet.getBytes("state"));
            loaded.version = resultSet.getLong("version");
            return loaded;
        }, conversationId);
        return state == null ? new ConversationState() : state;
    }

    @Override
    protected boolean persist(String conversationId, long expectedVersion, ConversationState state) {
        byte[] bytes = serializeState(state);
        if (expectedVersion == 0) {
            int inserted = jdbcTemplate.update(INSERT, conversationId, bytes, state.version);
            if (inserted == 1) {
                return true;
            }
        }
        return jdbcTemplate.update(
                UPDATE,
                bytes,
                state.version,
                conversationId,
                expectedVersion
        ) == 1;
    }

    @Override
    protected void remove(String conversationId) {
        jdbcTemplate.update(DELETE, conversationId);
    }
}
