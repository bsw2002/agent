package org.suvia.agent.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskIntentValidatorTest {

    private final TaskIntentValidator validator = new TaskIntentValidator();

    @Test
    void mapsClosedModelSchemaToTaskSpec() {
        TaskSpec result = validator.validate(new LlmIntentResult(
                "KNOWLEDGE_QUESTION",
                List.of("MODEL_REASONING", "KNOWLEDGE_RETRIEVAL"),
                "READ_ONLY",
                0.91,
                false
        ));

        assertEquals(TaskIntent.KNOWLEDGE_QUESTION, result.intent());
        assertEquals(RiskLevel.READ_ONLY, result.riskLevel());
        assertEquals(0.91, result.confidence());
    }

    @Test
    void rejectsUnknownCapabilityInsteadOfGrantingIt() {
        LlmIntentResult raw = new LlmIntentResult(
                "CONVERSATION",
                List.of("MODEL_REASONING", "DELETE_SYSTEM"),
                "READ_ONLY",
                0.8,
                false
        );

        assertThrows(IllegalArgumentException.class, () -> validator.validate(raw));
    }

    @Test
    void derivesRiskFromCapabilitiesInsteadOfTrustingModelRisk() {
        TaskSpec result = validator.validate(new LlmIntentResult(
                "CODE_EXECUTION",
                List.of("MODEL_REASONING", "TERMINAL_EXECUTION"),
                "READ_ONLY",
                0.9,
                false
        ));

        assertEquals(RiskLevel.PRIVILEGED_EXECUTION, result.riskLevel());
    }

    @Test
    void rejectsInconsistentIntentAndCapabilities() {
        LlmIntentResult raw = new LlmIntentResult(
                "CONVERSATION",
                List.of("MODEL_REASONING", "WEB_SEARCH"),
                "READ_ONLY",
                0.8,
                false
        );

        assertThrows(IllegalArgumentException.class, () -> validator.validate(raw));
    }
}
