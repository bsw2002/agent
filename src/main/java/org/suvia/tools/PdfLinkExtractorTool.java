package org.suvia.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.suvia.tools.result.ToolResult;
import org.suvia.tools.security.SafeUrlPolicy;
import org.suvia.tools.security.ToolPolicyViolationException;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public class PdfLinkExtractorTool {

    private final SafeUrlPolicy urlPolicy;
    private final Duration timeout;
    private final int maxContentChars;

    public PdfLinkExtractorTool(SafeUrlPolicy urlPolicy, Duration timeout, int maxContentChars) {
        this.urlPolicy = urlPolicy;
        this.timeout = timeout;
        this.maxContentChars = maxContentChars;
    }

    @Tool(description = "Extract a validated public PDF URL from a public web page")
    public ToolResult<Map<String, Object>> extractPdfLink(
            @ToolParam(description = "Public HTTP or HTTPS URL of the web page to parse") String url) {
        try {
            URI source = urlPolicy.validate(url);
            Document doc = Jsoup.connect(source.toString())
                    .userAgent("SuviaAgent/1.0")
                    .timeout((int) timeout.toMillis())
                    .followRedirects(false)
                    .maxBodySize(Math.max(1, maxContentChars * 4))
                    .get();

            Elements links = doc.select("a[href$=.pdf]");
            if (!links.isEmpty()) {
                return validatedResult(source, links.first().attr("href"));
            }

            links = doc.select("a:contains(PDF), a:contains(下载), a:contains(Download)");
            for (Element link : links) {
                String href = link.attr("href");
                if (href != null && !href.isEmpty() && !href.startsWith("javascript")) {
                    return validatedResult(source, href);
                }
            }

            Elements iframes = doc.select("iframe[src$=.pdf]");
            if (!iframes.isEmpty()) {
                return validatedResult(source, iframes.first().attr("src"));
            }

            return ToolResult.error("PDF_LINK_NOT_FOUND", "No PDF link was found on the page", false);
        } catch (ToolPolicyViolationException e) {
            return ToolResult.denied(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("PDF_LINK_EXTRACTION_FAILED", "Unable to inspect the web page", true);
        }
    }

    private ToolResult<Map<String, Object>> validatedResult(URI source, String extractedUrl) {
        URI resolved = source.resolve(extractedUrl);
        URI safe = urlPolicy.validate(resolved.toString());
        return ToolResult.success(Map.of("source", source.toString(), "pdfUrl", safe.toString()));
    }
}
