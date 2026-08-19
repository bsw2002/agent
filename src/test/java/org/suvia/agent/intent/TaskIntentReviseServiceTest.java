package org.suvia.agent.intent;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskIntentReviseServiceTest {

    private final RuleBasedTaskIntentClassifier rules = new RuleBasedTaskIntentClassifier();
    private final TaskIntentReviseService reviser = new TaskIntentReviseService(0.5);

    @Test
    void modelCannotInventTerminalPermission() {
        TaskSpec model = new TaskSpec(
                TaskIntent.CODE_EXECUTION,
                Set.of(Capability.MODEL_REASONING, Capability.TERMINAL_EXECUTION),
                RiskLevel.PRIVILEGED_EXECUTION,
                0.9,
                false
        );

        TaskSpec revised = reviser.revise("Explain what a shell is", model, rules.classify("Explain what a shell is"));

        assertFalse(revised.capabilities().contains(Capability.TERMINAL_EXECUTION));
        assertEquals(RiskLevel.READ_ONLY, revised.riskLevel());
    }

    @Test
    void explicitTerminalRequestCannotBeHiddenByModel() {
        String request = "在终端执行脚本";
        TaskSpec model = new TaskSpec(
                TaskIntent.CONVERSATION,
                Set.of(Capability.MODEL_REASONING),
                RiskLevel.READ_ONLY,
                0.8,
                false
        );

        TaskSpec revised = reviser.revise(request, model, rules.classify(request));

        assertTrue(revised.capabilities().contains(Capability.TERMINAL_EXECUTION));
        assertEquals(RiskLevel.PRIVILEGED_EXECUTION, revised.riskLevel());
    }

    @Test
    void explicitWebNegationOverridesModel() {
        String request = "不要联网，解释这个概念";
        TaskSpec model = new TaskSpec(
                TaskIntent.WEB_RESEARCH,
                Set.of(Capability.MODEL_REASONING, Capability.WEB_SEARCH, Capability.WEB_FETCH),
                RiskLevel.READ_ONLY,
                0.9,
                false
        );

        TaskSpec revised = reviser.revise(request, model, rules.classify(request));

        assertEquals(Set.of(Capability.MODEL_REASONING), revised.capabilities());
    }

    @Test
    void lowConfidenceRequiresClarification() {
        TaskSpec model = new TaskSpec(
                TaskIntent.CONVERSATION,
                Set.of(Capability.MODEL_REASONING),
                RiskLevel.READ_ONLY,
                0.3,
                false
        );

        TaskSpec revised = reviser.revise("分析一下这个方法", model, rules.classify("分析一下这个方法"));

        assertTrue(revised.requiresClarification());
    }
}
