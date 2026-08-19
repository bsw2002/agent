package org.suvia.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.suvia.security.RequestIdentity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcModelTraceRepository implements ModelTraceRepository {

    private static final String CALL_TABLE = """
            CREATE TABLE IF NOT EXISTS suvia_model_call_trace (
                call_id UUID PRIMARY KEY,
                trace_id UUID NOT NULL,
                run_id UUID,
                tenant_id VARCHAR(256) NOT NULL,
                user_id VARCHAR(256) NOT NULL,
                public_chat_id VARCHAR(128),
                scene VARCHAR(64) NOT NULL,
                call_type VARCHAR(32) NOT NULL,
                model_name VARCHAR(128),
                status VARCHAR(32) NOT NULL,
                input_sha256 CHAR(64) NOT NULL,
                input_preview TEXT,
                output_preview TEXT,
                output_dimension INTEGER,
                input_tokens BIGINT,
                output_tokens BIGINT,
                total_tokens BIGINT,
                duration_ms BIGINT NOT NULL,
                error_code VARCHAR(128),
                error_message VARCHAR(1000),
                started_at TIMESTAMPTZ NOT NULL,
                completed_at TIMESTAMPTZ NOT NULL
            )
            """;

    private static final String EVALUATION_TABLE = """
            CREATE TABLE IF NOT EXISTS suvia_trace_evaluation (
                evaluation_id UUID PRIMARY KEY,
                trace_id UUID NOT NULL,
                tenant_id VARCHAR(256) NOT NULL,
                user_id VARCHAR(256) NOT NULL,
                evaluator_type VARCHAR(32) NOT NULL,
                rule_score DOUBLE PRECISION,
                llm_judge_score DOUBLE PRECISION,
                human_score DOUBLE PRECISION,
                overall_score DOUBLE PRECISION,
                metrics JSONB NOT NULL,
                reason VARCHAR(2000),
                created_at TIMESTAMPTZ NOT NULL
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcModelTraceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        initializeSchema();
    }

    private void initializeSchema() {
        jdbcTemplate.execute(CALL_TABLE);
        jdbcTemplate.execute(EVALUATION_TABLE);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_suvia_model_trace_owner_time
                ON suvia_model_call_trace(tenant_id, user_id, started_at DESC)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_suvia_model_trace_trace
                ON suvia_model_call_trace(trace_id, started_at)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_suvia_trace_eval_owner_trace
                ON suvia_trace_evaluation(tenant_id, user_id, trace_id, created_at DESC)
                """);
    }

    @Override
    public void saveCall(ModelCallRecord call) {
        jdbcTemplate.update("""
                        INSERT INTO suvia_model_call_trace (
                            call_id, trace_id, run_id, tenant_id, user_id, public_chat_id,
                            scene, call_type, model_name, status, input_sha256,
                            input_preview, output_preview, output_dimension,
                            input_tokens, output_tokens, total_tokens, duration_ms,
                            error_code, error_message, started_at, completed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                call.callId(), call.traceId(), call.runId(), call.tenantId(), call.userId(), call.publicChatId(),
                call.scene().name(), call.callType().name(), call.modelName(), call.status().name(),
                call.inputSha256(), call.inputPreview(), call.outputPreview(), call.outputDimension(),
                call.inputTokens(), call.outputTokens(), call.totalTokens(), call.durationMs(),
                call.errorCode(), call.errorMessage(), Timestamp.from(call.startedAt()), Timestamp.from(call.completedAt())
        );
    }

    @Override
    public Optional<ModelCallRecord> findCallOwned(UUID callId, RequestIdentity identity) {
        return jdbcTemplate.query("""
                        SELECT * FROM suvia_model_call_trace
                        WHERE call_id = ? AND tenant_id = ? AND user_id = ?
                        """,
                (rs, rowNum) -> mapCall(rs),
                callId, identity.tenantId(), identity.userId()
        ).stream().findFirst();
    }

    @Override
    public List<ModelCallRecord> findTraceCallsOwned(UUID traceId, RequestIdentity identity) {
        return jdbcTemplate.query("""
                        SELECT * FROM suvia_model_call_trace
                        WHERE trace_id = ? AND tenant_id = ? AND user_id = ?
                        ORDER BY started_at, call_id
                        """,
                (rs, rowNum) -> mapCall(rs),
                traceId, identity.tenantId(), identity.userId()
        );
    }

    @Override
    public List<ModelCallRecord> searchOwned(
            RequestIdentity identity,
            String publicChatId,
            ModelCallScene scene,
            ModelCallStatus status,
            Instant startAt,
            Instant endAt,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM suvia_model_call_trace
                WHERE tenant_id = ? AND user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(identity.tenantId());
        args.add(identity.userId());
        if (publicChatId != null && !publicChatId.isBlank()) {
            sql.append(" AND public_chat_id = ?");
            args.add(publicChatId);
        }
        if (scene != null) {
            sql.append(" AND scene = ?");
            args.add(scene.name());
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        if (startAt != null) {
            sql.append(" AND started_at >= ?");
            args.add(Timestamp.from(startAt));
        }
        if (endAt != null) {
            sql.append(" AND started_at < ?");
            args.add(Timestamp.from(endAt));
        }
        sql.append(" ORDER BY started_at DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 500)));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapCall(rs), args.toArray());
    }

    @Override
    public void saveEvaluation(TraceEvaluationRecord evaluation) {
        jdbcTemplate.update("""
                        INSERT INTO suvia_trace_evaluation (
                            evaluation_id, trace_id, tenant_id, user_id, evaluator_type,
                            rule_score, llm_judge_score, human_score, overall_score,
                            metrics, reason, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?)
                        """,
                evaluation.evaluationId(), evaluation.traceId(), evaluation.tenantId(), evaluation.userId(),
                evaluation.evaluatorType(), evaluation.ruleScore(), evaluation.llmJudgeScore(),
                evaluation.humanScore(), evaluation.overallScore(), toJson(evaluation.metrics()),
                evaluation.reason(), Timestamp.from(evaluation.createdAt())
        );
    }

    @Override
    public List<TraceEvaluationRecord> findEvaluationsOwned(UUID traceId, RequestIdentity identity) {
        return jdbcTemplate.query("""
                        SELECT * FROM suvia_trace_evaluation
                        WHERE trace_id = ? AND tenant_id = ? AND user_id = ?
                        ORDER BY created_at DESC
                        """,
                (rs, rowNum) -> mapEvaluation(rs),
                traceId, identity.tenantId(), identity.userId()
        );
    }

    private ModelCallRecord mapCall(ResultSet rs) throws SQLException {
        return new ModelCallRecord(
                rs.getObject("call_id", UUID.class),
                rs.getObject("trace_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("public_chat_id"),
                ModelCallScene.valueOf(rs.getString("scene")),
                ModelCallType.valueOf(rs.getString("call_type")),
                rs.getString("model_name"),
                ModelCallStatus.valueOf(rs.getString("status")),
                rs.getString("input_sha256"),
                rs.getString("input_preview"),
                rs.getString("output_preview"),
                nullableInt(rs, "output_dimension"),
                nullableLong(rs, "input_tokens"),
                nullableLong(rs, "output_tokens"),
                nullableLong(rs, "total_tokens"),
                rs.getLong("duration_ms"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at").toInstant()
        );
    }

    private TraceEvaluationRecord mapEvaluation(ResultSet rs) throws SQLException {
        return new TraceEvaluationRecord(
                rs.getObject("evaluation_id", UUID.class),
                rs.getObject("trace_id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("evaluator_type"),
                nullableDouble(rs, "rule_score"),
                nullableDouble(rs, "llm_judge_score"),
                nullableDouble(rs, "human_score"),
                nullableDouble(rs, "overall_score"),
                parseMetrics(rs.getString("metrics")),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private String toJson(Map<String, Double> metrics) {
        try {
            return objectMapper.writeValueAsString(metrics == null ? Map.of() : metrics);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize evaluation metrics", error);
        }
    }

    private Map<String, Double> parseMetrics(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
