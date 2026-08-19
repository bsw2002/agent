package org.suvia.controller.dto;

import org.suvia.agent.intent.RiskLevel;
import org.suvia.agent.intent.TaskIntent;
import org.suvia.agent.runtime.RunStatus;
import org.suvia.orchestration.TaskExecutionMode;

import java.util.UUID;

public record TaskExecutionResult(
        String chatId,
        TaskExecutionMode mode,
        TaskIntent intent,
        RiskLevel riskLevel,
        double classifierConfidence,
        boolean clarificationRecommended,
        UUID runId,
        RunStatus runStatus,
        String content
) {
}
