package org.suvia.orchestration;

import org.springframework.stereotype.Service;
import org.suvia.agent.intent.Capability;
import org.suvia.agent.intent.TaskIntentClassifier;
import org.suvia.agent.intent.TaskSpec;
import org.suvia.agent.runtime.AgentRunCoordinator;
import org.suvia.agent.runtime.AgentRunRecord;
import org.suvia.app.AIApp;
import org.suvia.controller.dto.TaskExecutionResult;
import org.suvia.security.RequestIdentity;

import java.util.Set;

@Service
public class TaskOrchestrator {

    private final TaskIntentClassifier intentClassifier;
    private final TaskClarificationService clarificationService;
    private final AIApp aiApp;
    private final AgentRunCoordinator agentRuns;

    public TaskOrchestrator(
            TaskIntentClassifier intentClassifier,
            TaskClarificationService clarificationService,
            AIApp aiApp,
            AgentRunCoordinator agentRuns
    ) {
        this.intentClassifier = intentClassifier;
        this.clarificationService = clarificationService;
        this.aiApp = aiApp;
        this.agentRuns = agentRuns;
    }

    public TaskExecutionResult execute(
            String request,
            String publicChatId,
            String storageConversationId,
            RequestIdentity identity
    ) {
        TaskSpec taskSpec = intentClassifier.classify(request);
        if (taskSpec.requiresClarification()) {
            return result(
                    publicChatId,
                    taskSpec,
                    TaskExecutionMode.CLARIFICATION,
                    null,
                    clarificationService.question(taskSpec)
            );
        }
        if (taskSpec.capabilities().equals(Set.of(Capability.MODEL_REASONING))) {
            String content = aiApp.doChat(request, storageConversationId);
            return result(publicChatId, taskSpec, TaskExecutionMode.DIRECT_CHAT, null, content);
        }
        if (taskSpec.capabilities().equals(Set.of(
                Capability.MODEL_REASONING,
                Capability.KNOWLEDGE_RETRIEVAL
        ))) {
            String content = aiApp.doChatWithRag(request, storageConversationId);
            return result(
                    publicChatId,
                    taskSpec,
                    TaskExecutionMode.RETRIEVAL_AUGMENTED_CHAT,
                    null,
                    content
            );
        }

        AgentRunRecord run = agentRuns.execute(request, publicChatId, identity, taskSpec);
        return result(publicChatId, taskSpec, TaskExecutionMode.TOOL_AGENT, run, run.finalOutput());
    }

    private TaskExecutionResult result(
            String publicChatId,
            TaskSpec taskSpec,
            TaskExecutionMode mode,
            AgentRunRecord run,
            String content
    ) {
        return new TaskExecutionResult(
                publicChatId,
                mode,
                taskSpec.intent(),
                taskSpec.riskLevel(),
                taskSpec.confidence(),
                taskSpec.requiresClarification(),
                run == null ? null : run.runId(),
                run == null ? null : run.status(),
                content
        );
    }
}
