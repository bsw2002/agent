package org.suvia.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.suvia.security.RequestIdentity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAgentRunStore implements AgentRunStore {

    private static final String RUN_DDL = """
            CREATE TABLE IF NOT EXISTS suvia_agent_run (
                run_id UUID PRIMARY KEY,
                tenant_id VARCHAR(256) NOT NULL,
                user_id VARCHAR(256) NOT NULL,
                public_chat_id VARCHAR(128) NOT NULL,
                request_sha256 CHAR(64) NOT NULL,
                status VARCHAR(32) NOT NULL,
                current_step INTEGER NOT NULL DEFAULT 0,
                final_output TEXT,
                error_code VARCHAR(128),
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL
            )
            """;

    private static final String EVENT_DDL = """
            CREATE TABLE IF NOT EXISTS suvia_agent_event (
                event_id BIGSERIAL PRIMARY KEY,
                run_id UUID NOT NULL REFERENCES suvia_agent_run(run_id) ON DELETE CASCADE,
                event_type VARCHAR(64) NOT NULL,
                step_number INTEGER NOT NULL DEFAULT 0,
                payload JSONB NOT NULL,
                created_at TIMESTAMPTZ NOT NULL
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public JdbcAgentRunStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        initializeSchema();
    }

    private void initializeSchema() {
        jdbcTemplate.execute(RUN_DDL);
        jdbcTemplate.execute(EVENT_DDL);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_suvia_agent_run_owner
                ON suvia_agent_run(tenant_id, user_id, updated_at DESC)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_suvia_agent_event_run
                ON suvia_agent_event(run_id, event_id)
                """);
    }

    @Override
    public AgentRunRecord create(RequestIdentity identity, String publicChatId, String requestSha256) {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        AgentRunRecord record = new AgentRunRecord(
                runId,
                identity.tenantId(),
                identity.userId(),
                publicChatId,
                requestSha256,
                RunStatus.CREATED,
                0,
                null,
                null,
                now,
                now
        );
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                            INSERT INTO suvia_agent_run (
                                run_id, tenant_id, user_id, public_chat_id, request_sha256,
                                status, current_step, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    runId,
                    identity.tenantId(),
                    identity.userId(),
                    publicChatId,
                    requestSha256,
                    RunStatus.CREATED.name(),
                    0,
                    now,
                    now
            );
            insertEvent(runId, AgentEventType.RUN_CREATED, 0, Map.of());
        });
        return record;
    }

    @Override
    public void appendEvent(UUID runId, AgentEventType type, int stepNumber, Map<String, Object> payload) {
        insertEvent(runId, type, stepNumber, payload == null ? Map.of() : payload);
    }

    @Override
    public void checkpoint(UUID runId, RunStatus status, int currentStep) {
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            jdbcTemplate.update("""
                            UPDATE suvia_agent_run
                            SET status = ?, current_step = ?, updated_at = ?
                            WHERE run_id = ?
                            """,
                    status.name(), currentStep, Instant.now(), runId
            );
            insertEvent(runId, AgentEventType.CHECKPOINT_SAVED, currentStep, Map.of("status", status.name()));
        });
    }

    @Override
    public void complete(UUID runId, int currentStep, String finalOutput) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                            UPDATE suvia_agent_run
                            SET status = ?, current_step = ?, final_output = ?, error_code = NULL, updated_at = ?
                            WHERE run_id = ?
                            """,
                    RunStatus.SUCCEEDED.name(), currentStep, finalOutput, Instant.now(), runId
            );
            insertEvent(runId, AgentEventType.RUN_SUCCEEDED, currentStep, Map.of(
                    "responseCharacters", finalOutput == null ? 0 : finalOutput.length()
            ));
        });
    }

    @Override
    public void fail(UUID runId, int currentStep, String errorCode) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                            UPDATE suvia_agent_run
                            SET status = ?, current_step = ?, error_code = ?, updated_at = ?
                            WHERE run_id = ?
                            """,
                    RunStatus.FAILED.name(), currentStep, errorCode, Instant.now(), runId
            );
            insertEvent(runId, AgentEventType.RUN_FAILED, currentStep, Map.of("errorCode", errorCode));
        });
    }

    @Override
    public Optional<AgentRunRecord> findOwned(UUID runId, RequestIdentity identity) {
        List<AgentRunRecord> found = jdbcTemplate.query("""
                        SELECT run_id, tenant_id, user_id, public_chat_id, request_sha256,
                               status, current_step, final_output, error_code, created_at, updated_at
                        FROM suvia_agent_run
                        WHERE run_id = ? AND tenant_id = ? AND user_id = ?
                        """,
                (rs, rowNum) -> mapRun(rs),
                runId,
                identity.tenantId(),
                identity.userId()
        );
        return found.stream().findFirst();
    }

    @Override
    public List<AgentEventRecord> findEventsOwned(UUID runId, RequestIdentity identity) {
        return jdbcTemplate.query("""
                        SELECT e.event_id, e.run_id, e.event_type, e.step_number,
                               CAST(e.payload AS TEXT) AS payload, e.created_at
                        FROM suvia_agent_event e
                        JOIN suvia_agent_run r ON r.run_id = e.run_id
                        WHERE e.run_id = ? AND r.tenant_id = ? AND r.user_id = ?
                        ORDER BY e.event_id
                        """,
                (rs, rowNum) -> new AgentEventRecord(
                        rs.getLong("event_id"),
                        rs.getObject("run_id", UUID.class),
                        AgentEventType.valueOf(rs.getString("event_type")),
                        rs.getInt("step_number"),
                        rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                runId,
                identity.tenantId(),
                identity.userId()
        );
    }

    private void insertEvent(UUID runId, AgentEventType type, int stepNumber, Map<String, Object> payload) {
        jdbcTemplate.update("""
                        INSERT INTO suvia_agent_event(run_id, event_type, step_number, payload, created_at)
                        VALUES (?, ?, ?, CAST(? AS JSONB), ?)
                        """,
                runId,
                type.name(),
                stepNumber,
                toJson(payload),
                Instant.now()
        );
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize an agent event", e);
        }
    }

    private AgentRunRecord mapRun(ResultSet rs) throws SQLException {
        return new AgentRunRecord(
                rs.getObject("run_id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("public_chat_id"),
                rs.getString("request_sha256"),
                RunStatus.valueOf(rs.getString("status")),
                rs.getInt("current_step"),
                rs.getString("final_output"),
                rs.getString("error_code"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
