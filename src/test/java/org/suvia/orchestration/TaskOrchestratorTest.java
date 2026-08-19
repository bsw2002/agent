package org.suvia.orchestration;

import org.junit.jupiter.api.Test;
import org.suvia.agent.intent.RuleBasedTaskIntentClassifier;
import org.suvia.agent.runtime.AgentRunCoordinator;
import org.suvia.agent.runtime.AgentRunRecord;
import org.suvia.agent.runtime.RunStatus;
import org.suvia.app.AIApp;
import org.suvia.controller.dto.TaskExecutionResult;
import org.suvia.security.RequestIdentity;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskOrchestratorTest {

    private final AIApp aiApp = mock(AIApp.class);
    private final AgentRunCoordinator agentRuns = mock(AgentRunCoordinator.class);
    private final TaskOrchestrator orchestrator = new TaskOrchestrator(
            new RuleBasedTaskIntentClassifier(),
            new TaskClarificationService(),
            aiApp,
            agentRuns
    );
    private final RequestIdentity identity = new RequestIdentity("tenant", "user");

    @Test
    void routesOrdinaryQuestionToDirectChat() {
        when(aiApp.doChat("Explain dependency injection", "v1:scope"))
                .thenReturn("direct answer");

        TaskExecutionResult result = orchestrator.execute(
                "Explain dependency injection",
                "chat",
                "v1:scope",
                identity
        );

        assertEquals(TaskExecutionMode.DIRECT_CHAT, result.mode());
        assertEquals("direct answer", result.content());
        assertNull(result.runId());
        verifyNoInteractions(agentRuns);
    }

    @Test
    void routesPureKnowledgeQuestionToRag() {
        String request = "Answer from our knowledge base about the paper";
        when(aiApp.doChatWithRag(request, "v1:scope")).thenReturn("grounded answer");

        TaskExecutionResult result = orchestrator.execute(
                request,
                "chat",
                "v1:scope",
                identity
        );

        assertEquals(TaskExecutionMode.RETRIEVAL_AUGMENTED_CHAT, result.mode());
        assertEquals("grounded answer", result.content());
        verifyNoInteractions(agentRuns);
    }

    @Test
    void routesToolTaskToAuditedAgentRun() {
        String request = "Search the web for current information";
        UUID runId = UUID.randomUUID();
        AgentRunRecord run = new AgentRunRecord(
                runId,
                identity.tenantId(),
                identity.userId(),
                "chat",
                "hash",
                RunStatus.SUCCEEDED,
                2,
                "researched answer",
                null,
                Instant.now(),
                Instant.now()
        );
        when(agentRuns.execute(
                org.mockito.ArgumentMatchers.eq(request),
                org.mockito.ArgumentMatchers.eq("chat"),
                org.mockito.ArgumentMatchers.eq(identity),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(run);

        TaskExecutionResult result = orchestrator.execute(
                request,
                "chat",
                "v1:scope",
                identity
        );

        assertEquals(TaskExecutionMode.TOOL_AGENT, result.mode());
        assertEquals(runId, result.runId());
        assertEquals(RunStatus.SUCCEEDED, result.runStatus());
        verify(agentRuns).execute(
                org.mockito.ArgumentMatchers.eq(request),
                org.mockito.ArgumentMatchers.eq("chat"),
                org.mockito.ArgumentMatchers.eq(identity),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void returnsClarificationWithoutCallingModelOrAgent() {
        TaskExecutionResult result = orchestrator.execute(
                "帮我处理一下",
                "chat",
                "v1:scope",
                identity
        );

        assertEquals(TaskExecutionMode.CLARIFICATION, result.mode());
        assertEquals(true, result.clarificationRecommended());
        assertEquals("请说明你希望我执行哪类任务：检索论文、总结文献、分析文件，还是生成报告？", result.content());
        verifyNoInteractions(aiApp, agentRuns);
    }
}
