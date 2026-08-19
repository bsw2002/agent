package org.suvia.agent.intent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Uses Spring AI for semantic intent recognition and the existing rule
 * classifier as both safety evidence and an outage-safe fallback.
 */
@Component
@Primary
@Slf4j
public class HybridTaskIntentClassifier implements TaskIntentClassifier {

    private final LlmTaskIntentClassifier llmClassifier;
    private final RuleBasedTaskIntentClassifier ruleClassifier;
    private final TaskIntentReviseService reviseService;
    private final boolean llmEnabled;

    public HybridTaskIntentClassifier(
            LlmTaskIntentClassifier llmClassifier,
            RuleBasedTaskIntentClassifier ruleClassifier,
            TaskIntentReviseService reviseService,
            @Value("${suvia.intent.llm-enabled:true}") boolean llmEnabled
    ) {
        this.llmClassifier = llmClassifier;
        this.ruleClassifier = ruleClassifier;
        this.reviseService = reviseService;
        this.llmEnabled = llmEnabled;
    }

    @Override
    public TaskSpec classify(String request) {
        TaskSpec ruleResult = ruleClassifier.classify(request);
        if (!llmEnabled) {
            return ruleResult;
        }

        try {
            TaskSpec modelResult = llmClassifier.classify(request);
            return reviseService.revise(request, modelResult, ruleResult);
        } catch (RuntimeException error) {
            log.warn("Intent model classification failed; using deterministic fallback: errorType={}",
                    error.getClass().getSimpleName());
            return ruleResult;
        }
    }
}
