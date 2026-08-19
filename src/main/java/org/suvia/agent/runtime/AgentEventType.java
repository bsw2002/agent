package org.suvia.agent.runtime;

public enum AgentEventType {
    RUN_CREATED,
    RUN_STARTED,
    INTENT_CLASSIFIED,
    STEP_STARTED,
    STEP_COMPLETED,
    STEP_FAILED,
    CHECKPOINT_SAVED,
    RUN_SUCCEEDED,
    RUN_FAILED,
    RUN_CANCELLED
}
