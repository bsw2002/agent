package org.suvia.trace;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TraceEvaluationRecord(
        UUID evaluationId,
        UUID traceId,
        String tenantId,
        String userId,
        String evaluatorType,
        Double ruleScore,
        Double llmJudgeScore,
        Double humanScore,
        Double overallScore,
        Map<String, Double> metrics,
        String reason,
        Instant createdAt
) {
}
