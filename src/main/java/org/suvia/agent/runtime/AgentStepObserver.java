package org.suvia.agent.runtime;

import org.suvia.agent.model.AgentState;

public interface AgentStepObserver {

    AgentStepObserver NOOP = new AgentStepObserver() {
    };

    default void onStepStarted(int stepNumber, int maxSteps) {
    }

    default void onStepCompleted(int stepNumber, AgentState state) {
    }

    default void onStepFailed(int stepNumber, String errorCode) {
    }
}
