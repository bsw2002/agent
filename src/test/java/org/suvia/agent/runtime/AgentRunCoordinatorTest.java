package org.suvia.agent.runtime;

import org.junit.jupiter.api.Test;
import org.suvia.agent.BaseAgent;
import org.suvia.agent.model.AgentState;
import org.suvia.security.RequestIdentity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunCoordinatorTest {

    @Test
    void persistsLifecycleEventsAndACompletionCheckpoint() {
        InMemoryRunStore store = new InMemoryRunStore();
        AgentFactory factory = taskSpec -> new OneStepAgent("completed answer");
        AgentRunCoordinator coordinator = new AgentRunCoordinator(
                factory,
                store,
                request -> new org.suvia.agent.intent.RuleBasedTaskIntentClassifier().classify(request)
        );
        RequestIdentity identity = new RequestIdentity("tenant", "user");

        AgentRunRecord result = coordinator.execute("private request text", "chat-1", identity);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(1, result.currentStep());
        assertEquals("completed answer", result.finalOutput());
        assertNotEquals("private request text", result.requestSha256());
        assertEquals(64, result.requestSha256().length());

        List<AgentEventType> eventTypes = store.events.stream()
                .map(AgentEventRecord::eventType)
                .toList();
        assertTrue(eventTypes.contains(AgentEventType.RUN_CREATED));
        assertTrue(eventTypes.contains(AgentEventType.RUN_STARTED));
        assertTrue(eventTypes.contains(AgentEventType.INTENT_CLASSIFIED));
        assertTrue(eventTypes.contains(AgentEventType.STEP_STARTED));
        assertTrue(eventTypes.contains(AgentEventType.STEP_COMPLETED));
        assertTrue(eventTypes.contains(AgentEventType.CHECKPOINT_SAVED));
        assertTrue(eventTypes.contains(AgentEventType.RUN_SUCCEEDED));
        assertFalse(store.events.stream().anyMatch(event -> event.payloadJson().contains("private request text")));
    }

    @Test
    void persistsFailureWithoutReturningItAsAValidAnswer() {
        InMemoryRunStore store = new InMemoryRunStore();
        AgentFactory factory = taskSpec -> new FailingAgent();
        AgentRunCoordinator coordinator = new AgentRunCoordinator(
                factory,
                store,
                request -> new org.suvia.agent.intent.RuleBasedTaskIntentClassifier().classify(request)
        );
        RequestIdentity identity = new RequestIdentity("tenant", "user");

        AgentRunFailedException error = assertThrows(
                AgentRunFailedException.class,
                () -> coordinator.execute("request", "chat-1", identity)
        );

        AgentRunRecord persisted = store.runs.get(error.getRunId());
        assertEquals(RunStatus.FAILED, persisted.status());
        assertTrue(store.events.stream().anyMatch(event -> event.eventType() == AgentEventType.RUN_FAILED));
    }

    private static final class OneStepAgent extends BaseAgent {
        private final String answer;

        private OneStepAgent(String answer) {
            this.answer = answer;
        }

        @Override
        public String step() {
            setFinalOutput(answer);
            setState(AgentState.FINISHED);
            return answer;
        }
    }

    private static final class FailingAgent extends BaseAgent {
        @Override
        public String step() {
            throw new IllegalStateException("failure details must not become a final answer");
        }
    }

    private static final class InMemoryRunStore implements AgentRunStore {
        private final Map<UUID, AgentRunRecord> runs = new LinkedHashMap<>();
        private final List<AgentEventRecord> events = new ArrayList<>();
        private long eventSequence;

        @Override
        public AgentRunRecord create(RequestIdentity identity, String publicChatId, String requestSha256) {
            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            AgentRunRecord run = new AgentRunRecord(
                    id, identity.tenantId(), identity.userId(), publicChatId, requestSha256,
                    RunStatus.CREATED, 0, null, null, now, now
            );
            runs.put(id, run);
            appendEvent(id, AgentEventType.RUN_CREATED, 0, Map.of());
            return run;
        }

        @Override
        public void appendEvent(UUID runId, AgentEventType type, int stepNumber, Map<String, Object> payload) {
            events.add(new AgentEventRecord(
                    ++eventSequence,
                    runId,
                    type,
                    stepNumber,
                    String.valueOf(payload),
                    Instant.now()
            ));
        }

        @Override
        public void checkpoint(UUID runId, RunStatus status, int currentStep) {
            AgentRunRecord old = runs.get(runId);
            runs.put(runId, copy(old, status, currentStep, old.finalOutput(), old.errorCode()));
            appendEvent(runId, AgentEventType.CHECKPOINT_SAVED, currentStep, Map.of("status", status.name()));
        }

        @Override
        public void complete(UUID runId, int currentStep, String finalOutput) {
            AgentRunRecord old = runs.get(runId);
            runs.put(runId, copy(old, RunStatus.SUCCEEDED, currentStep, finalOutput, null));
            appendEvent(runId, AgentEventType.RUN_SUCCEEDED, currentStep, Map.of());
        }

        @Override
        public void fail(UUID runId, int currentStep, String errorCode) {
            AgentRunRecord old = runs.get(runId);
            runs.put(runId, copy(old, RunStatus.FAILED, currentStep, null, errorCode));
            appendEvent(runId, AgentEventType.RUN_FAILED, currentStep, Map.of("errorCode", errorCode));
        }

        @Override
        public Optional<AgentRunRecord> findOwned(UUID runId, RequestIdentity identity) {
            return Optional.ofNullable(runs.get(runId)).filter(run ->
                    run.tenantId().equals(identity.tenantId()) && run.userId().equals(identity.userId())
            );
        }

        @Override
        public List<AgentEventRecord> findEventsOwned(UUID runId, RequestIdentity identity) {
            if (findOwned(runId, identity).isEmpty()) {
                return List.of();
            }
            return events.stream().filter(event -> event.runId().equals(runId)).toList();
        }

        private AgentRunRecord copy(
                AgentRunRecord old,
                RunStatus status,
                int currentStep,
                String finalOutput,
                String errorCode
        ) {
            return new AgentRunRecord(
                    old.runId(), old.tenantId(), old.userId(), old.publicChatId(), old.requestSha256(),
                    status, currentStep, finalOutput, errorCode, old.createdAt(), Instant.now()
            );
        }
    }
}
