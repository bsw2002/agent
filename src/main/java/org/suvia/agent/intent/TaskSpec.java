package org.suvia.agent.intent;

import java.util.Set;

public record TaskSpec(
        TaskIntent intent,
        Set<Capability> capabilities,
        RiskLevel riskLevel,
        double confidence,
        boolean requiresClarification
) {
    public TaskSpec {
        capabilities = Set.copyOf(capabilities);
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("Intent confidence must be between 0 and 1");
        }
    }
}
