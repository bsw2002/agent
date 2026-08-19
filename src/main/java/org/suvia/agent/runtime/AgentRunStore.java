package org.suvia.agent.runtime;

import org.suvia.security.RequestIdentity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface AgentRunStore {

    AgentRunRecord create(RequestIdentity identity, String publicChatId, String requestSha256);

    void appendEvent(UUID runId, AgentEventType type, int stepNumber, Map<String, Object> payload);

    void checkpoint(UUID runId, RunStatus status, int currentStep);

    void complete(UUID runId, int currentStep, String finalOutput);

    void fail(UUID runId, int currentStep, String errorCode);

    Optional<AgentRunRecord> findOwned(UUID runId, RequestIdentity identity);

    List<AgentEventRecord> findEventsOwned(UUID runId, RequestIdentity identity);
}
