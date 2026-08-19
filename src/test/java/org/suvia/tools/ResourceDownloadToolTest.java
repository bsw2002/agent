package org.suvia.tools;

import org.junit.jupiter.api.Test;
import org.suvia.testsupport.WorkspaceTestSupport;
import org.suvia.tools.result.ToolResult;
import org.suvia.tools.result.ToolStatus;
import org.suvia.tools.security.SafePathResolver;
import org.suvia.tools.security.SafeUrlPolicy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceDownloadToolTest extends WorkspaceTestSupport {

    @Test
    void blocksPrivateNetworkDownloadsWithoutMakingARequest() {
        ResourceDownloadTool tool = new ResourceDownloadTool(
                new SafePathResolver(temporaryDirectory),
                new SafeUrlPolicy(List.of()),
                Duration.ofSeconds(1),
                1024
        );

        ToolResult<Map<String, Object>> result = tool.downloadResource(
                "http://127.0.0.1/private.pdf",
                "private.pdf"
        );

        assertEquals(ToolStatus.DENIED, result.status());
        assertEquals("PRIVATE_NETWORK_DENIED", result.error().code());
    }
}
