package org.suvia.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.suvia.tools.security.SafePathResolver;
import org.suvia.tools.security.SafeUrlPolicy;
import org.suvia.tools.security.ToolSecurityProperties;
import org.suvia.rag.HybridSearch.HybridRrfDocumentRetriever;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(ToolSecurityProperties.class)
public class ToolRegistration {

    @Value("${searchAPI.api-key}")
    private String searchApiKey;

    @Bean
    public ToolCallback[] allTools(
            ToolSecurityProperties properties,
            HybridRrfDocumentRetriever knowledgeRetriever
    ) {
        Path configuredRoot = Path.of(properties.getWorkspaceDirectory());
        Path workspaceRoot = configuredRoot.isAbsolute()
                ? configuredRoot
                : Path.of(System.getProperty("user.dir")).resolve(configuredRoot);
        SafePathResolver paths = new SafePathResolver(workspaceRoot);
        SafeUrlPolicy urlPolicy = new SafeUrlPolicy(properties.getAllowedDomains());
        Duration networkTimeout = Duration.ofSeconds(Math.max(1, properties.getNetworkTimeoutSeconds()));

        FileOperationTool fileOperationTool = new FileOperationTool(paths, properties.getMaxTextFileBytes());
        WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);
        WebScrapingTool webScrapingTool = new WebScrapingTool(
                urlPolicy,
                networkTimeout,
                properties.getMaxWebContentChars()
        );
        ResourceDownloadTool resourceDownloadTool = new ResourceDownloadTool(
                paths,
                urlPolicy,
                networkTimeout,
                properties.getMaxDownloadBytes()
        );
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool(paths);
        PdfLinkExtractorTool pdfLinkExtractorTool = new PdfLinkExtractorTool(
                urlPolicy,
                networkTimeout,
                properties.getMaxWebContentChars()
        );
        KnowledgeSearchTool knowledgeSearchTool = new KnowledgeSearchTool(knowledgeRetriever);

        List<Object> tools = new ArrayList<>(List.of(
                fileOperationTool,
                webSearchTool,
                webScrapingTool,
                resourceDownloadTool,
                pdfGenerationTool,
                pdfLinkExtractorTool,
                knowledgeSearchTool
        ));

        ToolSecurityProperties.Terminal terminal = properties.getTerminal();
        if (terminal.isEnabled()) {
            tools.add(new TerminalOperationTool(
                    true,
                    terminal.getAllowedExecutables(),
                    paths.root(),
                    Duration.ofSeconds(Math.max(1, terminal.getTimeoutSeconds())),
                    terminal.getMaxOutputChars()
            ));
        }

        return ToolCallbacks.from(tools.toArray());
    }
}
