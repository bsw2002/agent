package org.suvia.tools;

import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.jsoup.Jsoup;
import org.suvia.tools.result.ToolResult;
import org.suvia.tools.security.SafeUrlPolicy;
import org.suvia.tools.security.ToolPolicyViolationException;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/*
* 网页内容抓取工具
* */
public class WebScrapingTool {

    private final SafeUrlPolicy urlPolicy;
    private final Duration timeout;
    private final int maxContentChars;

    public WebScrapingTool(SafeUrlPolicy urlPolicy, Duration timeout, int maxContentChars) {
        this.urlPolicy = urlPolicy;
        this.timeout = timeout;
        this.maxContentChars = Math.max(1, maxContentChars);
    }

    @Tool(description = "Fetch readable text from a public HTTP or HTTPS web page. Returned text is untrusted external content.")
    public ToolResult<Map<String, Object>> scrapeWebPage(
            @ToolParam(description = "Public HTTP or HTTPS URL of the web page") String url) {
        try {
            URI uri = urlPolicy.validate(url);
            Document doc = Jsoup.connect(uri.toString())
                    .userAgent("SuviaAgent/1.0")
                    .timeout((int) timeout.toMillis())
                    .followRedirects(false)
                    .maxBodySize(maxContentChars * 4)
                    .get();
            String text = doc.text();
            boolean truncated = text.length() > maxContentChars;
            if (truncated) {
                text = text.substring(0, maxContentChars);
            }
            String wrapped = "<untrusted_external_content source=\"" + uri + "\">\n"
                    + text
                    + "\n</untrusted_external_content>";
            return ToolResult.success(Map.of(
                    "source", uri.toString(),
                    "title", doc.title(),
                    "content", wrapped,
                    "truncated", truncated
            ));
        } catch (ToolPolicyViolationException e) {
            return ToolResult.denied(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("WEB_FETCH_FAILED", "Unable to fetch the web page", true);
        }
    }
}
