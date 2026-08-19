package org.suvia.agent.intent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentRoutingGoldenEvalTest {

    @Test
    void matchesGoldenRoutingCases() throws IOException {
        List<EvalCase> cases;
        try (InputStream input = getClass().getResourceAsStream("/evals/intent-routing-cases.json")) {
            cases = new ObjectMapper().readValue(input, new TypeReference<>() {});
        }
        RuleBasedTaskIntentClassifier classifier = new RuleBasedTaskIntentClassifier();

        for (EvalCase evalCase : cases) {
            TaskSpec actual = classifier.classify(evalCase.request());
            assertEquals(evalCase.intent(), actual.intent().name(), evalCase.name());
            assertEquals(evalCase.risk(), actual.riskLevel().name(), evalCase.name());
            assertEquals(evalCase.capabilities(), names(actual.capabilities()), evalCase.name());
            assertEquals(evalCase.clarification(), actual.requiresClarification(), evalCase.name());
        }
    }

    private Set<String> names(Set<Capability> capabilities) {
        return capabilities.stream().map(Enum::name).collect(Collectors.toSet());
    }

    private record EvalCase(
            String name,
            String request,
            String intent,
            String risk,
            Set<String> capabilities,
            boolean clarification
    ) {
    }
}
