package org.suvia.agent.intent;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Locale;

/**
 * Converts untrusted model output to the application's closed enum set.
 * Invalid or unknown values fail the whole model classification so the hybrid
 * classifier can fall back to deterministic rules.
 */
@Component
public class TaskIntentValidator {

    public TaskSpec validate(LlmIntentResult raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Intent model returned no result");
        }

        TaskIntent declaredIntent = TaskIntent.valueOf(
                required(raw.intent(), "intent").toUpperCase(Locale.ROOT)
        );
        RiskLevel.valueOf(required(raw.riskLevel(), "riskLevel").toUpperCase(Locale.ROOT));

        EnumSet<Capability> capabilities = EnumSet.of(Capability.MODEL_REASONING);
        if (raw.capabilities() != null) {
            for (String capability : raw.capabilities()) {
                capabilities.add(Capability.valueOf(
                        required(capability, "capability").toUpperCase(Locale.ROOT)
                ));
            }
        }

        double confidence = raw.confidence() == null ? 0.5 : raw.confidence();
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("Intent confidence must be between 0 and 1");
        }

        TaskIntent derivedIntent = TaskIntentPolicy.determineIntent(capabilities);
        if (declaredIntent != derivedIntent) {
            throw new IllegalArgumentException(
                    "Intent model returned inconsistent intent and capabilities"
            );
        }

        return new TaskSpec(
                derivedIntent,
                capabilities,
                TaskIntentPolicy.determineRisk(capabilities),
                confidence,
                Boolean.TRUE.equals(raw.requiresClarification())
        );
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Intent model field is missing: " + field);
        }
        return value.strip();
    }
}
