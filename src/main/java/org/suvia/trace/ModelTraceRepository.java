package org.suvia.trace;

import org.suvia.security.RequestIdentity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModelTraceRepository {

    void saveCall(ModelCallRecord call);

    Optional<ModelCallRecord> findCallOwned(UUID callId, RequestIdentity identity);

    List<ModelCallRecord> findTraceCallsOwned(UUID traceId, RequestIdentity identity);

    List<ModelCallRecord> searchOwned(
            RequestIdentity identity,
            String publicChatId,
            ModelCallScene scene,
            ModelCallStatus status,
            Instant startAt,
            Instant endAt,
            int limit
    );

    void saveEvaluation(TraceEvaluationRecord evaluation);

    List<TraceEvaluationRecord> findEvaluationsOwned(UUID traceId, RequestIdentity identity);
}
