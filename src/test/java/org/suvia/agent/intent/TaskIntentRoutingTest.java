package org.suvia.agent.intent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskIntentRoutingTest {

    private final RuleBasedTaskIntentClassifier classifier = new RuleBasedTaskIntentClassifier();
    private final CapabilityToolRouter router = new CapabilityToolRouter();

    @Test
    void simpleConversationGetsNoTools() {
        TaskSpec spec = classifier.classify("Explain the difference between a process and a thread");
        ToolCallback[] tools = router.select(allTools(), spec);

        assertEquals(TaskIntent.CONVERSATION, spec.intent());
        assertEquals(RiskLevel.READ_ONLY, spec.riskLevel());
        assertEquals(0, tools.length);
    }

    @Test
    void webResearchGetsOnlyRequestedReadCapabilities() {
        TaskSpec spec = classifier.classify("请联网搜索最新资料并查看相关网页");
        Set<String> selected = names(router.select(allTools(), spec));

        assertTrue(spec.capabilities().contains(Capability.WEB_SEARCH));
        assertTrue(spec.capabilities().contains(Capability.WEB_FETCH));
        assertEquals(Set.of("searchWeb", "scrapeWebPage"), selected);
        assertFalse(selected.contains("writeFile"));
        assertEquals(RiskLevel.READ_ONLY, spec.riskLevel());
    }

    @Test
    void pdfGenerationDoesNotGrantTerminalOrUnrequestedFileWrite() {
        TaskSpec spec = classifier.classify("把内容生成 PDF");
        Set<String> selected = names(router.select(allTools(), spec));

        assertEquals(TaskIntent.DOCUMENT_GENERATION, spec.intent());
        assertEquals(Set.of("generatePDF"), selected);
        assertEquals(RiskLevel.WORKSPACE_WRITE, spec.riskLevel());
    }

    @Test
    void knowledgeQuestionGetsOnlyKnowledgeRetriever() {
        TaskSpec spec = classifier.classify("请从知识库查询这篇论文的方法");

        assertEquals(
                Set.of("searchKnowledgeBase"),
                names(router.select(allTools(), spec))
        );
    }

    @Test
    void terminalRequiresExplicitExecutionIntent() {
        TaskSpec normal = classifier.classify("Tell me what a shell is");
        TaskSpec execution = classifier.classify("在终端执行脚本");
        TaskSpec negated = classifier.classify("Do not execute a shell command; explain what it means");

        assertFalse(normal.capabilities().contains(Capability.TERMINAL_EXECUTION));
        assertFalse(negated.capabilities().contains(Capability.TERMINAL_EXECUTION));
        assertTrue(execution.capabilities().contains(Capability.TERMINAL_EXECUTION));
        assertEquals(Set.of("executeTerminalCommand"), names(router.select(allTools(), execution)));
        assertEquals(RiskLevel.PRIVILEGED_EXECUTION, execution.riskLevel());
    }

    @Test
    void vagueRequestRecommendsClarification() {
        TaskSpec spec = classifier.classify("帮我处理一下");

        assertTrue(spec.requiresClarification());
        assertTrue(spec.confidence() < 0.5);
    }

    @Test
    void unknownToolIsDeniedByDefault() {
        TaskSpec spec = classifier.classify("搜索网页");

        assertEquals(Set.of("searchWeb"), names(router.select(
                new ToolCallback[]{tool("searchWeb"), tool("newUnmappedPowerTool")},
                spec
        )));
    }

    private ToolCallback[] allTools() {
        return new ToolCallback[]{
                tool("readFile"),
                tool("writeFile"),
                tool("generatePDF"),
                tool("extractPdfLink"),
                tool("downloadResource"),
                tool("executeTerminalCommand"),
                tool("scrapeWebPage"),
                tool("searchWeb"),
                tool("searchKnowledgeBase")
        };
    }

    private Set<String> names(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(java.util.stream.Collectors.toSet());
    }

    private ToolCallback tool(String name) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description(name)
                .inputSchema("{}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String input) {
                return "{}";
            }
        };
    }
}
