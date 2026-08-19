package org.suvia.agent.intent;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class RuleBasedTaskIntentClassifier implements TaskIntentClassifier {

    private static final Pattern WEB_SEARCH = Pattern.compile(
            "搜索|检索|查找|联网|最新|新闻|search|research|look\\s*up|web",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern WEB_FETCH = Pattern.compile(
            "网页|网站|链接|url|website|webpage|scrape|crawl",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DOWNLOAD = Pattern.compile(
            "下载|保存.{0,8}(资源|附件)|download",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FILE_READ = Pattern.compile(
            "读取.{0,16}文件|查看.{0,16}文件|分析.{0,16}文件|read.{0,32}file|open.{0,32}file",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FILE_WRITE = Pattern.compile(
            "写入.{0,16}文件|修改.{0,16}文件|创建.{0,16}文件|保存到|write.{0,32}file|edit.{0,32}file|create.{0,32}file",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PDF_WRITE = Pattern.compile(
            "生成.{0,8}pdf|导出.{0,8}pdf|create.{0,8}pdf|generate.{0,8}pdf",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PDF_READ = Pattern.compile(
            "读取.{0,8}pdf|解析.{0,8}pdf|pdf.{0,8}(link|链接|extract)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TERMINAL = Pattern.compile(
            "(?:终端|命令行).{0,8}(?:执行|运行)|(?:执行|运行).{0,8}(?:命令|脚本)"
                    + "|(?:run|execute).{0,12}(?:shell|terminal|command|script)"
                    + "|(?:shell|terminal).{0,12}(?:run|execute)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern KNOWLEDGE = Pattern.compile(
            "知识库|文档库|论文|资料库|rag|knowledge\\s*base|document\\s*store",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern VAGUE = Pattern.compile(
            "^(帮我)?(处理|弄|搞|优化|看看)(一下)?[。.!！]?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NEGATED_WEB = Pattern.compile(
            "不要.{0,8}(联网|搜索|检索)|无需.{0,8}(联网|搜索|检索)|(?:do not|don't|without).{0,12}(?:search|web|internet)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NEGATED_WRITE = Pattern.compile(
            "不要.{0,8}(写入|修改|创建|保存)|禁止.{0,8}(写入|修改)|(?:do not|don't|without).{0,12}(?:write|edit|create|save)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NEGATED_PDF_WRITE = Pattern.compile(
            "不要.{0,8}(生成|导出).{0,4}pdf|无需.{0,8}(生成|导出).{0,4}pdf|(?:do not|don't|without).{0,12}(?:create|generate|export).{0,8}pdf",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NEGATED_DOWNLOAD = Pattern.compile(
            "不要.{0,8}下载|无需.{0,8}下载|(?:do not|don't|without).{0,12}download",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NEGATED_TERMINAL = Pattern.compile(
            "不要.{0,8}(执行|运行)|禁止.{0,8}(执行|运行)|(?:do not|don't|without).{0,12}(?:run|execute)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public TaskSpec classify(String request) {
        if (request == null || request.isBlank()) {
            throw new IllegalArgumentException("Request must not be blank");
        }
        String normalized = request.strip().toLowerCase(Locale.ROOT);
        EnumSet<Capability> capabilities = EnumSet.of(Capability.MODEL_REASONING);

        boolean webNegated = NEGATED_WEB.matcher(normalized).find();
        if (WEB_SEARCH.matcher(normalized).find() && !webNegated) capabilities.add(Capability.WEB_SEARCH);
        if (WEB_FETCH.matcher(normalized).find() && !webNegated) capabilities.add(Capability.WEB_FETCH);
        if (DOWNLOAD.matcher(normalized).find() && !NEGATED_DOWNLOAD.matcher(normalized).find()) {
            capabilities.add(Capability.RESOURCE_DOWNLOAD);
        }
        if (FILE_READ.matcher(normalized).find()) capabilities.add(Capability.FILE_READ);
        if (FILE_WRITE.matcher(normalized).find() && !NEGATED_WRITE.matcher(normalized).find()) {
            capabilities.add(Capability.FILE_WRITE);
        }
        if (PDF_WRITE.matcher(normalized).find() && !NEGATED_PDF_WRITE.matcher(normalized).find()) {
            capabilities.add(Capability.PDF_WRITE);
        }
        if (PDF_READ.matcher(normalized).find()) capabilities.add(Capability.PDF_READ);
        if (TERMINAL.matcher(normalized).find() && !NEGATED_TERMINAL.matcher(normalized).find()) {
            capabilities.add(Capability.TERMINAL_EXECUTION);
        }
        if (KNOWLEDGE.matcher(normalized).find()) capabilities.add(Capability.KNOWLEDGE_RETRIEVAL);

        TaskIntent intent = TaskIntentPolicy.determineIntent(capabilities);
        RiskLevel risk = TaskIntentPolicy.determineRisk(capabilities);
        boolean vague = VAGUE.matcher(normalized).matches();
        double confidence = vague ? 0.25 : capabilities.size() == 1 ? 0.65 : 0.9;
        return new TaskSpec(intent, capabilities, risk, confidence, vague);
    }

}
