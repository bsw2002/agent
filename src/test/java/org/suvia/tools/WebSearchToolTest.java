package org.suvia.tools;

import org.junit.jupiter.api.Test;
import org.suvia.tools.result.ToolResult;
import org.suvia.tools.result.ToolStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSearchToolTest {

    @Test
    void rejectsBlankQueriesWithoutCallingTheExternalService() {
        WebSearchTool tool = new WebSearchTool("unused-in-this-test");

        ToolResult<Map<String, Object>> result = tool.searchWeb("  ");

        assertEquals(ToolStatus.ERROR, result.status());
        assertEquals("INVALID_SEARCH_QUERY", result.error().code());
    }
}
