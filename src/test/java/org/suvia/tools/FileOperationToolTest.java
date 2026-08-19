package org.suvia.tools;

import org.junit.jupiter.api.Test;
import org.suvia.testsupport.WorkspaceTestSupport;
import org.suvia.tools.result.ToolResult;
import org.suvia.tools.result.ToolStatus;
import org.suvia.tools.security.SafePathResolver;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileOperationToolTest extends WorkspaceTestSupport {

    @Test
    void writesAndReadsInsideTheWorkspace() {
        FileOperationTool tool = new FileOperationTool(new SafePathResolver(temporaryDirectory), 1024);

        ToolResult<Map<String, Object>> write = tool.writeFile("notes/suvia.txt", "你好，我是suvia");
        ToolResult<Map<String, Object>> read = tool.readFile("notes/suvia.txt");

        assertEquals(ToolStatus.SUCCESS, write.status());
        assertEquals(ToolStatus.SUCCESS, read.status());
        assertEquals("你好，我是suvia", read.data().get("content"));
    }

    @Test
    void rejectsDirectoryTraversal() {
        FileOperationTool tool = new FileOperationTool(new SafePathResolver(temporaryDirectory), 1024);

        ToolResult<Map<String, Object>> result = tool.writeFile("../../outside.txt", "blocked");

        assertEquals(ToolStatus.DENIED, result.status());
        assertEquals("PATH_OUTSIDE_WORKSPACE", result.error().code());
    }
}
