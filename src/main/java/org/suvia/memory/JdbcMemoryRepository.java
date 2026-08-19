package org.suvia.memory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.suvia.security.RequestIdentity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcMemoryRepository implements MemoryRepository {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS suvia_memory (
                memory_id UUID PRIMARY KEY,
                tenant_id VARCHAR(256) NOT NULL,
                user_id VARCHAR(256) NOT NULL,
                scope_type VARCHAR(32) NOT NULL,
                scope_key VARCHAR(128) NOT NULL,
                memory_kind VARCHAR(32) NOT NULL,
                content TEXT NOT NULL,
                content_hash CHAR(64) NOT NULL,
                source VARCHAR(32) NOT NULL,
                confidence DOUBLE PRECISION NOT NULL,
                sensitivity VARCHAR(32) NOT NULL,
                status VARCHAR(32) NOT NULL,
                valid_from TIMESTAMPTZ NOT NULL,
                valid_until TIMESTAMPTZ,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                version BIGINT NOT NULL DEFAULT 1
            )
            """;
    private static final String CREATE_OWNER_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_suvia_memory_owner_active
            ON suvia_memory (tenant_id, user_id, status, updated_at DESC)
            """;
    private static final String CREATE_DEDUP_INDEX = """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_suvia_memory_active_content
            ON suvia_memory (
                tenant_id, user_id, scope_type, scope_key, memory_kind, content_hash
            ) WHERE status = 'ACTIVE'
            """;
    private static final String INSERT = """
            INSERT INTO suvia_memory (
                memory_id, tenant_id, user_id, scope_type, scope_key, memory_kind,
                content, content_hash, source, confidence, sensitivity, status,
                valid_from, valid_until, created_at, updated_at, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String FIND_OWNED = """
            SELECT * FROM suvia_memory
            WHERE memory_id = ? AND tenant_id = ? AND user_id = ?
            """;
    private static final String FIND_DUPLICATE = """
            SELECT * FROM suvia_memory
            WHERE tenant_id = ? AND user_id = ? AND scope_type = ? AND scope_key = ?
              AND memory_kind = ? AND content_hash = ? AND status = 'ACTIVE'
            """;
    private static final String FIND_CANDIDATES = """
            SELECT * FROM suvia_memory
            WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'
              AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
              AND (scope_type = 'USER' OR (scope_type = 'CONVERSATION' AND scope_key = ?))
            ORDER BY updated_at DESC
            LIMIT ?
            """;
    private static final String SOFT_DELETE = """
            UPDATE suvia_memory
            SET status = 'DELETED', updated_at = CURRENT_TIMESTAMP, version = version + 1
            WHERE memory_id = ? AND tenant_id = ? AND user_id = ?
              AND status = 'ACTIVE' AND version = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        jdbcTemplate.execute(CREATE_TABLE);
        jdbcTemplate.execute(CREATE_OWNER_INDEX);
        jdbcTemplate.execute(CREATE_DEDUP_INDEX);
    }

    @Override
    public MemoryRecord create(MemoryRecord memory) {
        try {
            jdbcTemplate.update(
                    INSERT,
                    memory.memoryId(),
                    memory.tenantId(),
                    memory.userId(),
                    memory.scope().name(),
                    memory.scopeKey(),
                    memory.kind().name(),
                    memory.content(),
                    memory.contentHash(),
                    memory.source().name(),
                    memory.confidence(),
                    memory.sensitivity().name(),
                    memory.status().name(),
                    memory.validFrom(),
                    memory.validUntil(),
                    memory.createdAt(),
                    memory.updatedAt(),
                    memory.version()
            );
            return memory;
        } catch (DuplicateKeyException duplicate) {
            return findDuplicate(memory).orElseThrow(() -> duplicate);
        }
    }

    @Override
    public Optional<MemoryRecord> findOwned(UUID memoryId, RequestIdentity identity) {
        return jdbcTemplate.query(
                FIND_OWNED,
                resultSet -> resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty(),
                memoryId,
                identity.tenantId(),
                identity.userId()
        );
    }

    @Override
    public List<MemoryRecord> findActiveCandidates(
            RequestIdentity identity,
            String conversationScopeKey,
            int limit
    ) {
        String scopeKey = conversationScopeKey == null ? "" : conversationScopeKey;
        return jdbcTemplate.query(
                FIND_CANDIDATES,
                (resultSet, rowNumber) -> map(resultSet),
                identity.tenantId(),
                identity.userId(),
                scopeKey,
                Math.max(1, Math.min(limit, 200))
        );
    }

    @Override
    public boolean softDeleteOwned(UUID memoryId, RequestIdentity identity, long expectedVersion) {
        return jdbcTemplate.update(
                SOFT_DELETE,
                memoryId,
                identity.tenantId(),
                identity.userId(),
                expectedVersion
        ) == 1;
    }

    private Optional<MemoryRecord> findDuplicate(MemoryRecord memory) {
        return jdbcTemplate.query(
                FIND_DUPLICATE,
                resultSet -> resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty(),
                memory.tenantId(),
                memory.userId(),
                memory.scope().name(),
                memory.scopeKey(),
                memory.kind().name(),
                memory.contentHash()
        );
    }

    private MemoryRecord map(ResultSet resultSet) throws SQLException {
        return new MemoryRecord(
                resultSet.getObject("memory_id", UUID.class),
                resultSet.getString("tenant_id"),
                resultSet.getString("user_id"),
                MemoryScope.valueOf(resultSet.getString("scope_type")),
                resultSet.getString("scope_key"),
                MemoryKind.valueOf(resultSet.getString("memory_kind")),
                resultSet.getString("content"),
                resultSet.getString("content_hash"),
                MemorySource.valueOf(resultSet.getString("source")),
                resultSet.getDouble("confidence"),
                MemorySensitivity.valueOf(resultSet.getString("sensitivity")),
                MemoryStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("valid_from", Instant.class),
                resultSet.getObject("valid_until", Instant.class),
                resultSet.getObject("created_at", Instant.class),
                resultSet.getObject("updated_at", Instant.class),
                resultSet.getLong("version")
        );
    }
}
