package org.suvia.trace;

import java.util.List;
import java.util.UUID;

public record TraceDetails(
        UUID traceId,
        String publicChatId,
        String status,
        int callCount,
        long durationMs,
        long totalTokens,
        List<ModelCallRecord> modelCalls,
        List<TraceEvaluationRecord> evaluations
) {
}
