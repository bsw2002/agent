package org.suvia.controller.dto;

import org.suvia.memory.MemoryKind;
import org.suvia.memory.MemoryRecord;
import org.suvia.memory.MemoryScope;
import org.suvia.memory.MemorySensitivity;
import org.suvia.memory.MemorySource;
import org.suvia.memory.MemoryStatus;

import java.time.Instant;
import java.util.UUID;

public record MemoryView(
        UUID memoryId,
        MemoryScope scope,
        MemoryKind kind,
        String content,
        MemorySource source,
        double confidence,
        MemorySensitivity sensitivity,
        MemoryStatus status,
        Instant updatedAt,
        long version
) {
    public static MemoryView from(MemoryRecord memory) {
        return new MemoryView(
                memory.memoryId(),
                memory.scope(),
                memory.kind(),
                memory.content(),
                memory.source(),
                memory.confidence(),
                memory.sensitivity(),
                memory.status(),
                memory.updatedAt(),
                memory.version()
        );
    }
}
