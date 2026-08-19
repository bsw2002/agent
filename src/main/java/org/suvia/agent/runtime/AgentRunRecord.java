package org.suvia.agent.runtime;

import java.time.Instant;
import java.util.UUID;

public record AgentRunRecord(
        UUID runId,
        String tenantId,
        String userId,
        String publicChatId,
        String requestSha256,
        RunStatus status,
        int currentStep,
        String finalOutput,
        String errorCode,
        Instant createdAt,
        Instant updatedAt
) {
}
