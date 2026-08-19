package org.suvia.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.suvia.tools.result.ToolStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeSearchToolTest {

    @Test
    void returnsBoundedDocumentsAsUntrustedData() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(query -> List.of(
                new Document("document text", Map.of("source", "paper.pdf"))
        ));

        var result = tool.searchKnowledgeBase("research question");

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals("UNTRUSTED_RETRIEVED_DATA", result.data().get("contentTrust"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) result.data().get("results");
        assertEquals(1, documents.size());
        assertEquals("document text", documents.getFirst().get("content"));
    }

    @Test
    void rejectsBlankQueryWithoutCallingRetriever() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(query -> {
            throw new AssertionError("retriever must not be called");
        });

        assertEquals(ToolStatus.ERROR, tool.searchKnowledgeBase(" ").status());
    }
}
