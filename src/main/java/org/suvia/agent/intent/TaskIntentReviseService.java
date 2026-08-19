package org.suvia.agent.intent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic post-processing inspired by diet-agent's IntentReviseService.
 * The model may propose read-only capabilities, but privileged/write
 * capabilities require explicit evidence from the rule classifier.
 */
@Component
public class TaskIntentReviseService {

    private static final Set<Capability> EXPLICIT_EVIDENCE_REQUIRED = Set.of(
            Capability.FILE_WRITE,
            Capability.PDF_WRITE,
            Capability.RESOURCE_DOWNLOAD,
            Capability.TERMINAL_EXECUTION
    );

    private static final Pattern NEGATED_WEB = Pattern.compile(
            "不要.{0,8}(联网|搜索|检索)|无需.{0,8}(联网|搜索|检索)|(?:do not|don't|without).{0,12}(?:search|web|internet)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NEGATED_KNOWLEDGE = Pattern.compile(
            "不要.{0,8}(知识库|文档库|论文库)|无需.{0,8}(知识库|文档库|论文库)|(?:do not|don't|without).{0,16}(?:knowledge base|document store)",
            Pattern.CASE_INSENSITIVE
    );

    private final double clarificationThreshold;

    public TaskIntentReviseService(
            @Value("${suvia.intent.clarification-threshold:0.5}") double clarificationThreshold
    ) {
        if (clarificationThreshold < 0 || clarificationThreshold > 1) {
            throw new IllegalArgumentException("Clarification threshold must be between 0 and 1");
        }
        this.clarificationThreshold = clarificationThreshold;
    }

    public TaskSpec revise(String request, TaskSpec candidate, TaskSpec ruleEvidence) {
        EnumSet<Capability> capabilities = candidate.capabilities().isEmpty()
                ? EnumSet.of(Capability.MODEL_REASONING)
                : EnumSet.copyOf(candidate.capabilities());
        capabilities.add(Capability.MODEL_REASONING);

        // A model cannot create or hide a privileged/write request. These
        // capabilities are accepted exactly when deterministic rules found
        // explicit evidence in the user's text.
        for (Capability capability : EXPLICIT_EVIDENCE_REQUIRED) {
            if (ruleEvidence.capabilities().contains(capability)) {
                capabilities.add(capability);
            } else {
                capabilities.remove(capability);
            }
        }

        String normalized = request == null ? "" : request.strip().toLowerCase(Locale.ROOT);
        if (NEGATED_WEB.matcher(normalized).find()) {
            capabilities.remove(Capability.WEB_SEARCH);
            capabilities.remove(Capability.WEB_FETCH);
        }
        if (NEGATED_KNOWLEDGE.matcher(normalized).find()) {
            capabilities.remove(Capability.KNOWLEDGE_RETRIEVAL);
        }

        boolean clarification = candidate.requiresClarification()
                || ruleEvidence.requiresClarification()
                || candidate.confidence() < clarificationThreshold;

        return new TaskSpec(
                TaskIntentPolicy.determineIntent(capabilities),
                capabilities,
                TaskIntentPolicy.determineRisk(capabilities),
                candidate.confidence(),
                clarification
        );
    }
}
