package org.suvia.agent.intent;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridTaskIntentClassifierTest {

    private final RuleBasedTaskIntentClassifier rules = new RuleBasedTaskIntentClassifier();
    private final TaskIntentReviseService reviser = new TaskIntentReviseService(0.5);

    @Test
    void usesValidatedModelResultForSemanticKnowledgeRequest() {
        LlmTaskIntentClassifier llm = mock(LlmTaskIntentClassifier.class);
        String request = "结合我上传的材料解释这个方法";
        when(llm.classify(request)).thenReturn(new TaskSpec(
                TaskIntent.KNOWLEDGE_QUESTION,
                Set.of(Capability.MODEL_REASONING, Capability.KNOWLEDGE_RETRIEVAL),
                RiskLevel.READ_ONLY,
                0.88,
                false
        ));
        HybridTaskIntentClassifier classifier = new HybridTaskIntentClassifier(llm, rules, reviser, true);

        TaskSpec result = classifier.classify(request);

        assertEquals(TaskIntent.KNOWLEDGE_QUESTION, result.intent());
        assertEquals(Set.of(Capability.MODEL_REASONING, Capability.KNOWLEDGE_RETRIEVAL), result.capabilities());
    }

    @Test
    void fallsBackToRulesWhenModelFails() {
        LlmTaskIntentClassifier llm = mock(LlmTaskIntentClassifier.class);
        String request = "Search the web for current prices";
        when(llm.classify(request)).thenThrow(new IllegalStateException("model unavailable"));
        HybridTaskIntentClassifier classifier = new HybridTaskIntentClassifier(llm, rules, reviser, true);

        TaskSpec result = classifier.classify(request);

        assertEquals(TaskIntent.WEB_RESEARCH, result.intent());
        assertEquals(Set.of(Capability.MODEL_REASONING, Capability.WEB_SEARCH), result.capabilities());
    }

    @Test
    void canDisableModelClassificationByConfiguration() {
        LlmTaskIntentClassifier llm = mock(LlmTaskIntentClassifier.class);
        HybridTaskIntentClassifier classifier = new HybridTaskIntentClassifier(llm, rules, reviser, false);

        TaskSpec result = classifier.classify("Generate a PDF report");

        assertEquals(TaskIntent.DOCUMENT_GENERATION, result.intent());
        assertEquals(RiskLevel.WORKSPACE_WRITE, result.riskLevel());
    }
}
