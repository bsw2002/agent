package org.suvia.agent.intent;

import java.util.Set;

/**
 * Derives execution-oriented intent and risk from the capabilities that were
 * accepted by the host application. Keeping this policy in Java prevents an
 * LLM response from lowering the risk of a privileged operation.
 */
final class TaskIntentPolicy {

    private TaskIntentPolicy() {
    }

    static TaskIntent determineIntent(Set<Capability> capabilities) {
        int specialized = capabilities.size() - (capabilities.contains(Capability.MODEL_REASONING) ? 1 : 0);
        if (specialized > 1) return TaskIntent.COMPOSITE_TASK;
        if (capabilities.contains(Capability.TERMINAL_EXECUTION)) return TaskIntent.CODE_EXECUTION;
        if (capabilities.contains(Capability.PDF_WRITE)) return TaskIntent.DOCUMENT_GENERATION;
        if (capabilities.contains(Capability.FILE_READ)
                || capabilities.contains(Capability.FILE_WRITE)
                || capabilities.contains(Capability.PDF_READ)) {
            return TaskIntent.FILE_TASK;
        }
        if (capabilities.contains(Capability.WEB_SEARCH)
                || capabilities.contains(Capability.WEB_FETCH)
                || capabilities.contains(Capability.RESOURCE_DOWNLOAD)) {
            return TaskIntent.WEB_RESEARCH;
        }
        if (capabilities.contains(Capability.KNOWLEDGE_RETRIEVAL)) return TaskIntent.KNOWLEDGE_QUESTION;
        return TaskIntent.CONVERSATION;
    }

    static RiskLevel determineRisk(Set<Capability> capabilities) {
        if (capabilities.contains(Capability.TERMINAL_EXECUTION)) {
            return RiskLevel.PRIVILEGED_EXECUTION;
        }
        if (capabilities.contains(Capability.FILE_WRITE)
                || capabilities.contains(Capability.PDF_WRITE)
                || capabilities.contains(Capability.RESOURCE_DOWNLOAD)) {
            return RiskLevel.WORKSPACE_WRITE;
        }
        return RiskLevel.READ_ONLY;
    }
}
