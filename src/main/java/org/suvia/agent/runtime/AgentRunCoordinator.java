package org.suvia.agent.runtime;

import org.springframework.stereotype.Service;
import org.suvia.agent.BaseAgent;
import org.suvia.agent.model.AgentState;
import org.suvia.agent.intent.TaskIntentClassifier;
import org.suvia.agent.intent.TaskSpec;
import org.suvia.exception.BusinessException;
import org.suvia.exception.ErrorCode;
import org.suvia.security.RequestIdentity;
import org.suvia.trace.ModelTraceContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentRunCoordinator {

    private final AgentFactory agentFactory;
    private final AgentRunStore runStore;
    private final TaskIntentClassifier intentClassifier;

    public AgentRunCoordinator(
            AgentFactory agentFactory,
            AgentRunStore runStore,
            TaskIntentClassifier intentClassifier
    ) {
        this.agentFactory = agentFactory;
        this.runStore = runStore;
        this.intentClassifier = intentClassifier;
    }

    public AgentRunRecord execute(
            String request,
            String publicChatId,
            RequestIdentity identity
    ) {
        return execute(request, publicChatId, identity, intentClassifier.classify(request));
    }

    public AgentRunRecord execute(
            String request,
            String publicChatId,
            RequestIdentity identity,
            TaskSpec taskSpec
    ) {
        AgentRunRecord created = runStore.create(identity, publicChatId, sha256(request));
        UUID runId = created.runId();
        ModelTraceContext.attachRunId(runId);
        runStore.appendEvent(runId, AgentEventType.RUN_STARTED, 0, Map.of());
        runStore.checkpoint(runId, RunStatus.RUNNING, 0);

        BaseAgent agent = null;
        try {
            runStore.appendEvent(runId, AgentEventType.INTENT_CLASSIFIED, 0, Map.of(
                    "intent", taskSpec.intent().name(),
                    "capabilities", taskSpec.capabilities().stream().map(Enum::name).sorted().toList(),
                    "riskLevel", taskSpec.riskLevel().name(),
                    "confidence", taskSpec.confidence(),
                    "requiresClarification", taskSpec.requiresClarification()
            ));

            agent = agentFactory.create(taskSpec);
            agent.setStepObserver(new PersistingStepObserver(runId));
            String finalOutput = agent.run(request);
            runStore.complete(runId, agent.getCurrentStep(), finalOutput);
            return runStore.findOwned(runId, identity).orElseThrow();
        } catch (RuntimeException e) {
            int currentStep = agent == null ? 0 : agent.getCurrentStep();
            runStore.fail(runId, currentStep, normalizeErrorCode(e));
            throw new AgentRunFailedException(runId, e);
        }
    }

    public AgentRunRecord getOwnedRun(UUID runId, RequestIdentity identity) {
        return runStore.findOwned(runId, identity)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR));
    }

    public List<AgentEventRecord> getOwnedEvents(UUID runId, RequestIdentity identity) {
        getOwnedRun(runId, identity);
        return runStore.findEventsOwned(runId, identity);
    }

    private String normalizeErrorCode(RuntimeException error) {
        String simpleName = error.getClass().getSimpleName();
        return simpleName.length() > 128 ? simpleName.substring(0, 128) : simpleName;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private final class PersistingStepObserver implements AgentStepObserver {
        private final UUID runId;

        private PersistingStepObserver(UUID runId) {
            this.runId = runId;
        }

        @Override
        public void onStepStarted(int stepNumber, int maxSteps) {
            runStore.appendEvent(
                    runId,
                    AgentEventType.STEP_STARTED,
                    stepNumber,
                    Map.of("maxSteps", maxSteps)
            );
        }

        @Override
        public void onStepCompleted(int stepNumber, AgentState state) {
            runStore.appendEvent(
                    runId,
                    AgentEventType.STEP_COMPLETED,
                    stepNumber,
                    Map.of("agentState", state.name())
            );
            // A completed step is only a checkpoint. The run becomes SUCCEEDED
            // when complete(...) persists the final output atomically.
            runStore.checkpoint(runId, RunStatus.RUNNING, stepNumber);
        }

        @Override
        public void onStepFailed(int stepNumber, String errorCode) {
            runStore.appendEvent(
                    runId,
                    AgentEventType.STEP_FAILED,
                    stepNumber,
                    Map.of("errorCode", errorCode)
            );
        }
    }
}
