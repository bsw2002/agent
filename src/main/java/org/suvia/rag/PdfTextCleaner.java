package org.suvia.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * PDF 正文清洗：尽量去掉页码/页眉页脚/版权声明/DOI等噪声。
 * 注意：规则不可能 100% 通用，你后续需要根据自己的论文样本微调。
 */
class PdfTextCleaner {

    // 常见“整行噪声”模式（命中则整行删除）
    private static final List<Pattern> DROP_LINE_PATTERNS = List.of(
            // 纯页码：1 / 12 / iii / IV 等
            Pattern.compile("^\\s*\\d{1,4}\\s*$"),
            Pattern.compile("^\\s*[ivxlcdmIVXLCDM]{1,8}\\s*$"),

            // Page x / Page x of y
            Pattern.compile("^\\s*page\\s*\\d{1,4}(\\s*(of|/)\\s*\\d{1,4})?\\s*$", Pattern.CASE_INSENSITIVE),

            // 第x页 / 第x页/共y页
            Pattern.compile("^\\s*第\\s*\\d{1,4}\\s*页(\\s*/\\s*共\\s*\\d{1,4}\\s*页)?\\s*$"),

            // DOI 行（很多 PDF 页脚会重复 DOI）
            Pattern.compile("^\\s*(doi\\s*:|https?://doi\\.org/)\\S+\\s*$", Pattern.CASE_INSENSITIVE),

            // 版权/出版社/许可（按你的论文来源可继续加）
            Pattern.compile("^\\s*copyright\\s+.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*all\\s+rights\\s+reserved\\s*.*$", Pattern.CASE_INSENSITIVE),

            // 会议/期刊页眉常见：Proceedings of ... / Springer / Elsevier 等（按需增删）
            Pattern.compile("^\\s*proceedings\\s+of\\s+.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*(springer|elsevier|ieee|acm)\\s*.*$", Pattern.CASE_INSENSITIVE),

            // 期刊头/卷期/年月（如：第41卷 第9期 ... Vol. 41 No. 9 / Sep. 2017）
            Pattern.compile("^\\s*第\\s*\\d+\\s*卷\\s*第\\s*\\d+\\s*期.*$"),
            Pattern.compile("^\\s*vol\\.?\\s*\\d+\\s*no\\.?\\s*\\d+.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*\\d{4}\\s*年\\s*\\d{1,2}\\s*月.*$"),
            
            // 文章编号/中图分类号/文献标志码/学科代码
            Pattern.compile("^\\s*文章编号[:：].*$"),
            Pattern.compile("^\\s*中图分类号[:：].*$"),
            Pattern.compile("^\\s*文献标志码[:：].*$"),
            Pattern.compile("^\\s*学科代码[:：].*$"),
            
            // 中英文摘要关键词标题行
            Pattern.compile("^\\s*摘要[:：].*$"),
            Pattern.compile("^\\s*关键词[:：].*$"),
            Pattern.compile("^\\s*abstract[:：].*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*key\\s*words?[:：].*$", Pattern.CASE_INSENSITIVE),
            
            // 基金项目 / Project Supported
            Pattern.compile("^\\s*基金项目[:：].*$"),
            Pattern.compile("^\\s*project\\s+supported\\s+by.*$", Pattern.CASE_INSENSITIVE),
            
            // 图表标题行（图 1 ... / Fig. 1 ...）
            Pattern.compile("^\\s*图\\s*\\d+\\s+.*$"),
            Pattern.compile("^\\s*fig\\.?\\s*\\d+\\s+.*$", Pattern.CASE_INSENSITIVE),
            
            // 页脚类：期刊名 + 页码（如：... Vol. 41 No. 9 2930）
            Pattern.compile("^\\s*.*vol\\.?\\s*\\d+\\s*no\\.?\\s*\\d+\\s*\\d{3,5}\\s*$", Pattern.CASE_INSENSITIVE)


    );

    // 一些“行内噪声”（替换为空），例如重复的 DOI、页码标记等
    private static final List<Pattern> INLINE_NOISE_PATTERNS = List.of(
            Pattern.compile("https?://doi\\.org/\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdoi\\s*:\\s*\\S+\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("第\\s*\\d{1,4}\\s*页", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpage\\s*\\d{1,4}(\\s*(of|/)\\s*\\d{1,4})?\\b", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 清洗一页（或一段）文本：按行删除噪声 + 行内替换 + 规范空白。
     */
    String clean(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        String[] lines = rawText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> kept = new ArrayList<>(lines.length);

        for (String line : lines) {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty()) {
                continue;
            }

            if (shouldDropLine(t)) {
                continue;
            }

            String cleaned = t;
            for (Pattern p : INLINE_NOISE_PATTERNS) {
                cleaned = p.matcher(cleaned).replaceAll("");
            }

            cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
            if (!cleaned.isEmpty()) {
                kept.add(cleaned);
            }
        }

        // 合并成段落：用换行保留一定结构
        return String.join("\n", kept).trim();
    }

    private boolean shouldDropLine(String trimmedLine) {
        for (Pattern p : DROP_LINE_PATTERNS) {
            if (p.matcher(trimmedLine).matches()) {
                return true;
            }
        }
        return false;
    }
}