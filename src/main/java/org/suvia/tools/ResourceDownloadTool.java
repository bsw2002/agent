package org.suvia.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.suvia.tools.result.ToolResult;
import org.suvia.tools.security.SafePathResolver;
import org.suvia.tools.security.SafeUrlPolicy;
import org.suvia.tools.security.ToolPolicyViolationException;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;

public class ResourceDownloadTool {

    private final SafePathResolver paths;
    private final SafeUrlPolicy urlPolicy;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final long maxDownloadBytes;

    public ResourceDownloadTool(
            SafePathResolver workspacePaths,
            SafeUrlPolicy urlPolicy,
            Duration timeout,
            long maxDownloadBytes
    ) {
        this.paths = new SafePathResolver(workspacePaths.root().resolve("download"));
        this.urlPolicy = urlPolicy;
        this.timeout = timeout;
        this.maxDownloadBytes = Math.max(1, maxDownloadBytes);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Tool(description = "Download a resource from a given URL")
    public ToolResult<Map<String, Object>> downloadResource(
            @ToolParam(description = "Public HTTP or HTTPS URL of the resource") String url,
            @ToolParam(description = "Workspace-relative file name for the downloaded resource") String fileName) {
        try {
            URI uri = urlPolicy.validate(url);
            Path destination = paths.resolve(fileName);
            Files.createDirectories(destination.getParent());

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(timeout)
                    .header("User-Agent", "SuviaAgent/1.0")
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                return ToolResult.error("HTTP_STATUS", "Remote server returned HTTP " + response.statusCode(), true);
            }

            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declaredLength > maxDownloadBytes) {
                response.body().close();
                return ToolResult.error("DOWNLOAD_TOO_LARGE", "The resource exceeds the configured limit", false);
            }

            Path temporary = Files.createTempFile(destination.getParent(), ".agent-download-", ".tmp");
            long copied;
            try (InputStream input = response.body()) {
                copied = copyWithLimit(input, temporary);
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }

            return ToolResult.success(Map.of(
                    "path", fileName,
                    "sizeBytes", copied,
                    "contentType", response.headers().firstValue("Content-Type").orElse("application/octet-stream")
            ));
        } catch (ToolPolicyViolationException e) {
            return ToolResult.denied(e.getCode(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("DOWNLOAD_INTERRUPTED", "The download was interrupted", true);
        } catch (Exception e) {
            return ToolResult.error("DOWNLOAD_FAILED", "Unable to download the resource", true);
        }
    }

    private long copyWithLimit(InputStream input, Path temporary) throws Exception {
        byte[] buffer = new byte[8192];
        long total = 0;
        try (var output = Files.newOutputStream(temporary)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxDownloadBytes) {
                    throw new ToolPolicyViolationException(
                            "DOWNLOAD_TOO_LARGE",
                            "The resource exceeds the configured limit"
                    );
                }
                output.write(buffer, 0, read);
            }
        }
        return total;
    }
}
