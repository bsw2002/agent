package org.suvia.trace;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelTraceContextTest {

    @Test
    void propagatesRequestMetadataIntoDeferredStreamingSubscription() {
        UUID traceId = UUID.randomUUID();
        Flux<ModelTraceContext.Snapshot> deferred;

        try (ModelTraceContext.Scope trace = ModelTraceContext.openTrace(traceId);
             ModelTraceContext.Scope scene = ModelTraceContext.openScene(ModelCallScene.RAG_ANSWER)) {
            ModelTraceContext.attachOwner("tenant-a", "alice");
            ModelTraceContext.attachConversation("chat-1");
            deferred = ModelTraceContext.propagate(Flux.defer(() -> Flux.just(
                    ModelTraceContext.snapshot("fallback-tenant", "fallback-user", ModelCallScene.UNKNOWN)
            )));
        }

        ModelTraceContext.Snapshot snapshot = deferred.blockFirst();
        assertEquals(traceId, snapshot.traceId());
        assertEquals("tenant-a", snapshot.tenantId());
        assertEquals("alice", snapshot.userId());
        assertEquals("chat-1", snapshot.publicChatId());
        assertEquals(ModelCallScene.RAG_ANSWER, snapshot.scene());
    }
}
