package org.suvia.trace;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.suvia.security.RequestIdentity;
import org.suvia.security.RequestIdentityResolver;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCallTraceAspectTest {

    private ModelTraceContext.Scope openTrace;

    @AfterEach
    void closeTrace() {
        if (openTrace != null) openTrace.close();
    }

    @Test
    void recordsChatMetadataAndRedactsCapturedSecrets() throws Throwable {
        InMemoryRepository repository = new InMemoryRepository();
        ModelCallTraceAspect aspect = aspect(repository, true);
        UUID traceId = UUID.randomUUID();
        openTrace = ModelTraceContext.openTrace(traceId);
        ModelTraceContext.attachConversation("chat-1");

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("answer"))),
                ChatResponseMetadata.builder()
                        .model("qwen-plus")
                        .usage(new DefaultUsage(10, 4, 14))
                        .build()
        );
        when(joinPoint.proceed()).thenReturn(response);

        try (ModelTraceContext.Scope ignored = ModelTraceContext.openScene(ModelCallScene.INTENT_CLASSIFICATION)) {
            assertEquals(response, aspect.traceChatCall(joinPoint, new Prompt("api-key=top-secret")));
        }

        ModelCallRecord call = repository.calls.getFirst();
        assertEquals(traceId, call.traceId());
        assertEquals("chat-1", call.publicChatId());
        assertEquals(ModelCallScene.INTENT_CLASSIFICATION, call.scene());
        assertEquals("qwen-plus", call.modelName());
        assertEquals(14L, call.totalTokens());
        assertTrue(call.inputPreview().contains("[REDACTED]"));
        assertEquals("answer", call.outputPreview());
        assertNotNull(call.inputSha256());
    }

    @Test
    void recordsFailedChatCallWithoutHidingOriginalFailure() throws Throwable {
        InMemoryRepository repository = new InMemoryRepository();
        ModelCallTraceAspect aspect = aspect(repository, true);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("provider failed"));

        assertThrows(
                IllegalStateException.class,
                () -> aspect.traceChatCall(joinPoint, new Prompt("question"))
        );

        ModelCallRecord call = repository.calls.getFirst();
        assertEquals(ModelCallStatus.FAILED, call.status());
        assertEquals("IllegalStateException", call.errorCode());
        assertTrue(call.errorMessage().contains("provider failed"));
    }

    @Test
    void recordsEmbeddingDimensionWithoutPersistingDocumentContent() throws Throwable {
        InMemoryRepository repository = new InMemoryRepository();
        ModelCallTraceAspect aspect = aspect(repository, true);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        EmbeddingResponse response = new EmbeddingResponse(
                List.of(new Embedding(new float[1536], 0)),
                new EmbeddingResponseMetadata("text-embedding-v1", new DefaultUsage(8, 0, 8))
        );
        when(joinPoint.proceed()).thenReturn(response);

        aspect.traceEmbeddingCall(
                joinPoint,
                new EmbeddingRequest(List.of("private paper text"), null)
        );

        ModelCallRecord call = repository.calls.getFirst();
        assertEquals(ModelCallType.EMBEDDING, call.callType());
        assertEquals(1536, call.outputDimension());
        assertEquals("text-embedding-v1", call.modelName());
        assertNull(call.inputPreview());
        assertNotNull(call.inputSha256());
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsStreamingCallWhenPublisherCompletes() throws Throwable {
        InMemoryRepository repository = new InMemoryRepository();
        ModelCallTraceAspect aspect = aspect(repository, true);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        ChatResponse first = new ChatResponse(List.of(new Generation(new AssistantMessage("part-"))));
        ChatResponse last = new ChatResponse(
                List.of(new Generation(new AssistantMessage("answer"))),
                ChatResponseMetadata.builder()
                        .model("qwen-plus")
                        .usage(new DefaultUsage(10, 2, 12))
                        .build()
        );
        when(joinPoint.proceed()).thenReturn(reactor.core.publisher.Flux.just(first, last));

        reactor.core.publisher.Flux<ChatResponse> traced =
                (reactor.core.publisher.Flux<ChatResponse>) aspect.traceChatStream(
                        joinPoint,
                        new Prompt("question")
                );
        traced.collectList().block();

        ModelCallRecord call = repository.calls.getFirst();
        assertEquals(ModelCallType.CHAT_STREAM, call.callType());
        assertEquals(ModelCallStatus.SUCCESS, call.status());
        assertEquals("part-answer", call.outputPreview());
        assertEquals(12L, call.totalTokens());
    }

    @Test
    void pointcutInterceptsSpringAiChatModelProxy() {
        InMemoryRepository repository = new InMemoryRepository();
        ModelCallTraceAspect aspect = aspect(repository, true);
        ChatModel target = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));
        when(target.call(any(Prompt.class))).thenReturn(response);

        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(aspect);
        ChatModel proxy = proxyFactory.getProxy();

        assertEquals(response, proxy.call(new Prompt("question")));
        assertEquals(1, repository.calls.size());
        assertEquals(ModelCallType.CHAT, repository.calls.getFirst().callType());
    }

    private ModelCallTraceAspect aspect(InMemoryRepository repository, boolean captureContent) {
        RequestIdentityResolver resolver = mock(RequestIdentityResolver.class);
        when(resolver.resolve(any())).thenReturn(new RequestIdentity("tenant-a", "alice"));
        return new ModelCallTraceAspect(
                repository,
                resolver,
                true,
                captureContent,
                4000,
                "qwen-plus",
                "text-embedding-v1"
        );
    }

    private static final class InMemoryRepository implements ModelTraceRepository {
        private final List<ModelCallRecord> calls = new ArrayList<>();

        @Override public void saveCall(ModelCallRecord call) { calls.add(call); }
        @Override public Optional<ModelCallRecord> findCallOwned(UUID id, RequestIdentity identity) { return Optional.empty(); }
        @Override public List<ModelCallRecord> findTraceCallsOwned(UUID id, RequestIdentity identity) { return List.of(); }
        @Override public List<ModelCallRecord> searchOwned(RequestIdentity identity, String chatId,
                ModelCallScene scene, ModelCallStatus status, Instant start, Instant end, int limit) { return List.of(); }
        @Override public void saveEvaluation(TraceEvaluationRecord evaluation) { }
        @Override public List<TraceEvaluationRecord> findEvaluationsOwned(UUID id, RequestIdentity identity) {
            return List.of();
        }
    }
}
