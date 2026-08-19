package org.suvia.controller.dto;

import org.suvia.agent.runtime.RunStatus;

import java.util.UUID;

public record AgentRunResult(
        UUID runId,
        String chatId,
        RunStatus status,
        int currentStep,
        String content,
        String errorCode
) {
}
