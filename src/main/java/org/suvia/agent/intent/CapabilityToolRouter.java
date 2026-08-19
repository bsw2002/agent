package org.suvia.agent.intent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;

@Component
public class CapabilityToolRouter {

    private static final Map<String, Capability> TOOL_CAPABILITIES = Map.of(
            "readFile", Capability.FILE_READ,
            "writeFile", Capability.FILE_WRITE,
            "generatePDF", Capability.PDF_WRITE,
            "extractPdfLink", Capability.PDF_READ,
            "downloadResource", Capability.RESOURCE_DOWNLOAD,
            "executeTerminalCommand", Capability.TERMINAL_EXECUTION,
            "scrapeWebPage", Capability.WEB_FETCH,
            "searchWeb", Capability.WEB_SEARCH,
            "searchKnowledgeBase", Capability.KNOWLEDGE_RETRIEVAL
    );

    public ToolCallback[] select(ToolCallback[] registeredTools, TaskSpec taskSpec) {
        if (registeredTools == null || registeredTools.length == 0) {
            return new ToolCallback[0];
        }
        return Arrays.stream(registeredTools)
                .filter(tool -> {
                    String name = tool.getToolDefinition().name();
                    Capability required = TOOL_CAPABILITIES.get(name);
                    return required != null && taskSpec.capabilities().contains(required);
                })
                .toArray(ToolCallback[]::new);
    }
}
