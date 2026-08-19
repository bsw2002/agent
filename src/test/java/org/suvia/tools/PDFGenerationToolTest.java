package org.suvia.tools;

import org.junit.jupiter.api.Test;
import org.suvia.testsupport.WorkspaceTestSupport;
import org.suvia.tools.result.ToolResult;
import org.suvia.tools.result.ToolStatus;
import org.suvia.tools.security.SafePathResolver;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PDFGenerationToolTest extends WorkspaceTestSupport {

    @Test
    void rejectsNonPdfAndEscapingPathsBeforeWriting() {
        PDFGenerationTool tool = new PDFGenerationTool(new SafePathResolver(temporaryDirectory));

        ToolResult<Map<String, Object>> wrongExtension = tool.generatePDF("report.txt", "content");
        ToolResult<Map<String, Object>> traversal = tool.generatePDF("../../report.pdf", "content");

        assertEquals(ToolStatus.DENIED, wrongExtension.status());
        assertEquals(ToolStatus.DENIED, traversal.status());
    }
}
