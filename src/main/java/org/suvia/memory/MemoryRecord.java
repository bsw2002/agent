package org.suvia.memory;

import java.time.Instant;
import java.util.UUID;

public record MemoryRecord(
        UUID memoryId,
        String tenantId,
        String userId,
        MemoryScope scope,
        String scopeKey,
        MemoryKind kind,
        String content,
        String contentHash,
        MemorySource source,
        double confidence,
        MemorySensitivity sensitivity,
        MemoryStatus status,
        Instant validFrom,
        Instant validUntil,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
