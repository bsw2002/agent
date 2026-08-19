package org.suvia.trace;

import java.time.Instant;
import java.util.UUID;

public record ModelCallRecord(
        UUID callId,
        UUID traceId,
        UUID runId,
        String tenantId,
        String userId,
        String publicChatId,
        ModelCallScene scene,
        ModelCallType callType,
        String modelName,
        ModelCallStatus status,
        String inputSha256,
        String inputPreview,
        String outputPreview,
        Integer outputDimension,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        long durationMs,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt
) {
}
