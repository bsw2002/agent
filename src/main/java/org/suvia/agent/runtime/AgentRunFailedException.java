package org.suvia.agent.runtime;

import org.suvia.agent.exception.AgentExecutionException;

import java.util.UUID;

public class AgentRunFailedException extends AgentExecutionException {

    private final UUID runId;

    public AgentRunFailedException(UUID runId, Throwable cause) {
        super("Agent run failed: " + runId, cause);
        this.runId = runId;
    }

    public UUID getRunId() {
        return runId;
    }
}
