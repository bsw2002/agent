package org.suvia.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.suvia.tools.result.ToolResult;
import org.suvia.tools.security.SafePathResolver;
import org.suvia.tools.security.ToolPolicyViolationException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public class FileOperationTool {

    private final SafePathResolver paths;
    private final long maxTextFileBytes;

    public FileOperationTool(SafePathResolver workspacePaths, long maxTextFileBytes) {
        this.paths = new SafePathResolver(workspacePaths.root().resolve("file"));
        this.maxTextFileBytes = Math.max(1, maxTextFileBytes);
    }

    @Tool(description = "Read content from a file")
    public ToolResult<Map<String, Object>> readFile(
            @ToolParam(description = "Workspace-relative name of the UTF-8 text file to read") String fileName) {
        try {
            Path file = paths.resolve(fileName);
            long size = Files.size(file);
            if (size > maxTextFileBytes) {
                return ToolResult.error("FILE_TOO_LARGE", "The file exceeds the configured text-file limit", false);
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return ToolResult.success(Map.of(
                    "path", fileName,
                    "sizeBytes", size,
                    "content", content
            ));
        } catch (ToolPolicyViolationException e) {
            return ToolResult.denied(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("FILE_READ_FAILED", "Unable to read the requested file", false);
        }
    }

    @Tool(description = "Write content to a file")
    public ToolResult<Map<String, Object>> writeFile(
        @ToolParam(description = "Workspace-relative name of the file to write") String fileName,
        @ToolParam(description = "Content to write to the file") String content) {
        try {
            byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maxTextFileBytes) {
                return ToolResult.error("FILE_TOO_LARGE", "The content exceeds the configured text-file limit", false);
            }

            Path file = paths.resolve(fileName);
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), ".agent-write-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }

            return ToolResult.success(Map.of("path", fileName, "sizeBytes", bytes.length));
        } catch (ToolPolicyViolationException e) {
            return ToolResult.denied(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("FILE_WRITE_FAILED", "Unable to write the requested file", false);
        }
    }
}
