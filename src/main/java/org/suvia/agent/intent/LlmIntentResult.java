package org.suvia.agent.intent;

import java.util.List;

/**
 * Raw structured result returned by the intent model. Strings are deliberately
 * kept untrusted until {@link TaskIntentValidator} maps them to host enums.
 */
public record LlmIntentResult(
        String intent,
        List<String> capabilities,
        String riskLevel,
        Double confidence,
        Boolean requiresClarification
) {
}
