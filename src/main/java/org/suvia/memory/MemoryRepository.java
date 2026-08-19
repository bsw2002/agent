package org.suvia.memory;

import org.suvia.security.RequestIdentity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemoryRepository {
    MemoryRecord create(MemoryRecord memory);

    Optional<MemoryRecord> findOwned(UUID memoryId, RequestIdentity identity);

    List<MemoryRecord> findActiveCandidates(
            RequestIdentity identity,
            String conversationScopeKey,
            int limit
    );

    boolean softDeleteOwned(UUID memoryId, RequestIdentity identity, long expectedVersion);
}
