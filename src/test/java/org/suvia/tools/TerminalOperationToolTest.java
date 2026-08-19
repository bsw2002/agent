package org.suvia.tools;

import org.junit.jupiter.api.Test;
import org.suvia.testsupport.WorkspaceTestSupport;
import org.suvia.tools.result.ToolResult;
import org.suvia.tools.result.ToolStatus;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalOperationToolTest extends WorkspaceTestSupport {

    @Test
    void isDeniedWhenDisabled() {
        TerminalOperationTool tool = new TerminalOperationTool(
                false,
                List.of(),
                temporaryDirectory,
                Duration.ofSeconds(1),
                1024
        );

        ToolResult<Map<String, Object>> result = tool.executeTerminalCommand("cmd", List.of("/c", "dir"));

        assertEquals(ToolStatus.DENIED, result.status());
        assertEquals("TERMINAL_DISABLED", result.error().code());
    }

    @Test
    void rejectsExecutablesOutsideTheAllowlist() {
        TerminalOperationTool tool = new TerminalOperationTool(
                true,
                List.of("git"),
                temporaryDirectory,
                Duration.ofSeconds(1),
                1024
        );

        ToolResult<Map<String, Object>> result = tool.executeTerminalCommand("powershell", List.of());

        assertEquals(ToolStatus.DENIED, result.status());
        assertEquals("EXECUTABLE_DENIED", result.error().code());
    }
}
