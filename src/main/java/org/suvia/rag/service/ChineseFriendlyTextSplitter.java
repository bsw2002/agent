package org.suvia.rag.service;

import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 面向中文文献/PDF：按换行段落优先切分，过长段落再按固定字符窗口滑动切分（码点安全），
 * 窗口内尽量在「。」或换行处截断。不依赖 jtokkit CL100K，避免英文 tokenizer 与中文混排时的边界问题。
 */
class ChineseFriendlyTextSplitter extends TextSplitter {

    private final int chunkSize;
    private final int chunkOverlap;

    private static final Pattern LINE_ENDINGS = Pattern.compile("\r\n?");

    ChineseFriendlyTextSplitter(int chunkSize, int chunkOverlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须为正数");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap 须满足 0 <= overlap < chunkSize");
        }
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    /** 默认与 {@code MyTokenTextSplitter} 中 application 配置保持一致；若未走 Spring 注入可单独调大。 */
    ChineseFriendlyTextSplitter() {
        this(2800, 280);
    }

    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = LINE_ENDINGS.matcher(text.trim()).replaceAll("\n");
        List<String> paragraphs = splitParagraphs(normalized);
        List<String> out = new ArrayList<>();
        for (String p : paragraphs) {
            if (p.codePointCount(0, p.length()) <= chunkSize) {
                out.add(p.trim());
            } else {
                out.addAll(splitByFixedWindow(p));
            }
        }
        return out;
    }

    /**
     * 按空行切成段落；若无空行则整段作为一块后续再切。
     */
    private List<String> splitParagraphs(String text) {
        String[] parts = text.split("\n{2,}");
        List<String> list = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                list.add(part);
            }
        }
        if (list.isEmpty()) {
            list.add(text);
        }
        return list;
    }

    /**
     * 滑动窗口（按码点计长度），块之间带重叠；窗口内优先在句号或换行处截短。
     */
    private List<String> splitByFixedWindow(String text) {
        List<String> chunks = new ArrayList<>();
        int cpCount = text.codePointCount(0, text.length());
        int startCp = 0;
        while (startCp < cpCount) {
            int endCp = Math.min(startCp + chunkSize, cpCount);
            int startIdx = text.offsetByCodePoints(0, startCp);
            int endIdx = text.offsetByCodePoints(0, endCp);
            String window = text.substring(startIdx, endIdx);
            int lastStop = Math.max(window.lastIndexOf('。'), window.lastIndexOf('\n'));
            if (lastStop > chunkSize / 4 && lastStop < window.length() - 1) {
                window = window.substring(0, lastStop + 1);
                endCp = startCp + window.codePointCount(0, window.length());
            }
            if (!window.isBlank()) {
                chunks.add(window.trim());
            }
            if (endCp >= cpCount) {
                break;
            }
            int nextStart = endCp - chunkOverlap;
            if (nextStart <= startCp) {
                nextStart = startCp + Math.max(1, chunkSize - chunkOverlap);
            }
            startCp = nextStart;
        }
        return chunks;
    }
}
