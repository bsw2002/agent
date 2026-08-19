package org.suvia.agent.runtime;

public enum RunStatus {
    CREATED,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_USER,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
