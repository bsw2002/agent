package org.suvia.trace;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.suvia.exception.BusinessException;
import org.suvia.security.RequestIdentity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ModelTraceServiceTest {

    private final RequestIdentity alice = new RequestIdentity("tenant-a", "alice");

    @Test
    void evaluatesARecordedTraceWithDeterministicMetrics() {
        InMemoryRepository repository = new InMemoryRepository();
        UUID traceId = UUID.randomUUID();
        repository.calls.add(call(traceId, alice, ModelCallStatus.SUCCESS, "answer", 1200, 500L));
        ModelTraceService service = new ModelTraceService(repository, mock(ChatModel.class));

        TraceEvaluationRecord result = service.evaluate(traceId, alice, false);

        assertEquals("RULE", result.evaluatorType());
        assertEquals(5.0, result.ruleScore());
        assertEquals(5.0, result.overallScore());
        assertEquals(1, repository.evaluations.size());
        assertEquals(1, service.getTrace(traceId, alice).callCount());
    }

    @Test
    void storesHumanLabelAndKeepsOwnerIsolation() {
        InMemoryRepository repository = new InMemoryRepository();
        UUID traceId = UUID.randomUUID();
        repository.calls.add(call(traceId, alice, ModelCallStatus.SUCCESS, "answer", 100, 20L));
        ModelTraceService service = new ModelTraceService(repository, mock(ChatModel.class));

        TraceEvaluationRecord label = service.label(traceId, alice, new TraceLabelRequest(4.5, "useful"));

        assertEquals(4.5, label.humanScore());
        assertEquals("useful", label.reason());
        assertThrows(
                BusinessException.class,
                () -> service.getTrace(traceId, new RequestIdentity("tenant-a", "bob"))
        );
    }

    @Test
    void failedAndEmptyCallsLowerRuleScore() {
        InMemoryRepository repository = new InMemoryRepository();
        UUID traceId = UUID.randomUUID();
        repository.calls.add(call(traceId, alice, ModelCallStatus.FAILED, null, 25_000, 20_000L));
        ModelTraceService service = new ModelTraceService(repository, mock(ChatModel.class));

        TraceEvaluationRecord result = service.evaluate(traceId, alice, false);

        assertTrue(result.ruleScore() < 2.0);
    }

    @Test
    void contentDisabledDoesNotTreatRedactedOutputAsEmpty() {
        InMemoryRepository repository = new InMemoryRepository();
        UUID traceId = UUID.randomUUID();
        repository.calls.add(call(traceId, alice, ModelCallStatus.SUCCESS, null, 100, 20L));
        ModelTraceService service = new ModelTraceService(repository, mock(ChatModel.class));

        TraceEvaluationRecord result = service.evaluate(traceId, alice, false);

        assertEquals(5.0, result.ruleScore());
        assertTrue(!result.metrics().containsKey("nonEmptyOutput"));
    }

    @Test
    void rejectsLlmJudgeWhenTraceContentWasNotCaptured() {
        InMemoryRepository repository = new InMemoryRepository();
        UUID traceId = UUID.randomUUID();
        repository.calls.add(call(traceId, alice, ModelCallStatus.SUCCESS, null, 100, 20L));
        ModelTraceService service = new ModelTraceService(repository, mock(ChatModel.class));

        assertThrows(BusinessException.class, () -> service.evaluate(traceId, alice, true));
    }

    private ModelCallRecord call(
            UUID traceId,
            RequestIdentity owner,
            ModelCallStatus status,
            String output,
            long duration,
            Long tokens
    ) {
        Instant now = Instant.now();
        return new ModelCallRecord(
                UUID.randomUUID(), traceId, null, owner.tenantId(), owner.userId(), "chat-1",
                ModelCallScene.DIRECT_CHAT, ModelCallType.CHAT, "qwen-plus", status,
                "a".repeat(64), "question", output, null,
                10L, 10L, tokens, duration,
                status == ModelCallStatus.FAILED ? "ProviderError" : null,
                status == ModelCallStatus.FAILED ? "failed" : null,
                now, now
        );
    }

    private static final class InMemoryRepository implements ModelTraceRepository {
        private final List<ModelCallRecord> calls = new ArrayList<>();
        private final List<TraceEvaluationRecord> evaluations = new ArrayList<>();

        @Override public void saveCall(ModelCallRecord call) { calls.add(call); }
        @Override public Optional<ModelCallRecord> findCallOwned(UUID id, RequestIdentity identity) {
            return calls.stream().filter(call -> call.callId().equals(id)).filter(call -> owned(call, identity)).findFirst();
        }
        @Override public List<ModelCallRecord> findTraceCallsOwned(UUID id, RequestIdentity identity) {
            return calls.stream().filter(call -> call.traceId().equals(id)).filter(call -> owned(call, identity)).toList();
        }
        @Override public List<ModelCallRecord> searchOwned(RequestIdentity identity, String chatId,
                ModelCallScene scene, ModelCallStatus status, Instant start, Instant end, int limit) {
            return calls.stream().filter(call -> owned(call, identity)).limit(limit).toList();
        }
        @Override public void saveEvaluation(TraceEvaluationRecord evaluation) { evaluations.add(evaluation); }
        @Override public List<TraceEvaluationRecord> findEvaluationsOwned(UUID id, RequestIdentity identity) {
            return evaluations.stream().filter(value -> value.traceId().equals(id))
                    .filter(value -> value.tenantId().equals(identity.tenantId()))
                    .filter(value -> value.userId().equals(identity.userId())).toList();
        }
        private boolean owned(ModelCallRecord call, RequestIdentity identity) {
            return call.tenantId().equals(identity.tenantId()) && call.userId().equals(identity.userId());
        }
    }
}
