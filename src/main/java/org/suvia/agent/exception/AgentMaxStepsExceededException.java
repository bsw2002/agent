package org.suvia.agent.exception;

public class AgentMaxStepsExceededException extends AgentExecutionException {

    public AgentMaxStepsExceededException(int maxSteps) {
        super("Agent reached the maximum number of steps: " + maxSteps);
    }
}
