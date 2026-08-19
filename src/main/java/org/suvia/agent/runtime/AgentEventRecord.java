package org.suvia.agent.runtime;

import java.time.Instant;
import java.util.UUID;

public record AgentEventRecord(
        long eventId,
        UUID runId,
        AgentEventType eventType,
        int stepNumber,
        String payloadJson,
        Instant createdAt
) {
}
