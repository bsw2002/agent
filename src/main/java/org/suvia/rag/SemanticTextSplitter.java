package org.suvia.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 语义分割（不依赖标题）：
 * 1) 把文本切成“句子”
 * 2) 再把句子打包成“小段”（每段长度受控），对每小段做 embedding
 * 3) 比较相邻小段 embedding 的余弦相似度：
 *    相似度低于阈值 => 认为主题突变 => 在该位置断块
 * 4) 最终再合并成 chunk（带 overlap）
 *
 * 说明：
 * - 这类“语义切分”在入库阶段会多做 embedding 调用，所以会比纯字符切分慢一些。
 * - 你可以通过 suvia.rag.semantic-split.* 参数调大/调小 chunkSize 与阈值来控制块数。
 */
@Component
@Slf4j
class SemanticTextSplitter extends TextSplitter {

    private final EmbeddingModel embeddingModel;

    private final int chunkSizeChars;      // 最终 chunk 目标大小（字符，使用 codePointCount 更安全）
    private final int chunkOverlapChars;   // overlap
    private final int minChunkChars;       // 过短则丢弃/不切

    // “语义判断”粒度：把句子打包成 embedding 小段
    private final int embedGroupMaxChars;     // embedding 小段最多多长（字符）
    private final int embedGroupMaxSentences; // embedding 小段最多包含多少句
    private final double semanticThreshold;   // 余弦相似度阈值（越高 => 切得越多）

    private static final Pattern LINE_ENDINGS = Pattern.compile("\r\n?");

    // 把句子分割到标点之后：包含中文/英文常见句末符
    private static final Pattern SENTENCE_SPLIT_AFTER_PUNCT =
            Pattern.compile("(?<=[。！？；.!?;])");

    SemanticTextSplitter(
            EmbeddingModel embeddingModel,
            @Value("${suvia.rag.semantic-split.chunk-size-chars}") int chunkSizeChars,
            @Value("${suvia.rag.semantic-split.chunk-overlap-chars}") int chunkOverlapChars,
            @Value("${suvia.rag.semantic-split.min-chunk-chars}") int minChunkChars,
            @Value("${suvia.rag.semantic-split.embed-group-max-chars}") int embedGroupMaxChars,
            @Value("${suvia.rag.semantic-split.embed-group-max-sentences}") int embedGroupMaxSentences,
            @Value("${suvia.rag.semantic-split.threshold}") double semanticThreshold
    ) {
        if (chunkSizeChars <= 0) throw new IllegalArgumentException("chunkSizeChars 必须为正数");
        if (chunkOverlapChars < 0 || chunkOverlapChars >= chunkSizeChars) {
            throw new IllegalArgumentException("chunkOverlapChars 必须满足 0 <= overlap < chunkSizeChars");
        }
        this.embeddingModel = embeddingModel;
        this.chunkSizeChars = chunkSizeChars;
        this.chunkOverlapChars = chunkOverlapChars;
        this.minChunkChars = minChunkChars;
        this.embedGroupMaxChars = embedGroupMaxChars;
        this.embedGroupMaxSentences = embedGroupMaxSentences;
        this.semanticThreshold = semanticThreshold;
    }

    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) return List.of();

        String normalized = LINE_ENDINGS.matcher(text).replaceAll("\n").trim();
        List<String> sentences = splitToSentences(normalized);
        if (sentences.size() <= 1) {
            return mergeToChunks(Arrays.asList(normalized));
        }

        // 1) 先构建 embedding 小段（控制长度/句子数），同时记录每句属于哪个小段
        List<String> embedGroups = new ArrayList<>();
        List<Integer> groupSentenceEndIndex = new ArrayList<>();
        int[] groupIndexBySentence = new int[sentences.size()];
        Arrays.fill(groupIndexBySentence, -1);

        int curGroup = 0;
        int startSentence = 0;

        while (startSentence < sentences.size()) {
            int endExclusive = startSentence;
            int curCp = 0;
            int curSentenceCount = 0;

            StringBuilder groupText = new StringBuilder();

            while (endExclusive < sentences.size()) {
                String s = sentences.get(endExclusive).trim();
                if (s.isEmpty()) {
                    endExclusive++;
                    continue;
                }

                int sCp = cpLen(s);
                // 小段长度上限/句子上限
                if (curSentenceCount >= embedGroupMaxSentences) break;
                if (curCp + sCp > embedGroupMaxChars && curSentenceCount > 0) break;

                if (groupText.length() > 0) groupText.append(' ');
                groupText.append(s);

                curCp += sCp;
                curSentenceCount++;
                endExclusive++;
            }

            if (groupText.length() == 0) {
                // 防御：避免死循环
                endExclusive = startSentence + 1;
                String s = sentences.get(startSentence).trim();
                embedGroups.add(s);
                groupSentenceEndIndex.add(startSentence);
                groupIndexBySentence[startSentence] = curGroup;
                startSentence++;
                curGroup++;
                continue;
            }

            String built = groupText.toString().trim();
            embedGroups.add(built);
            int groupEnd = endExclusive - 1;
            groupSentenceEndIndex.add(groupEnd);

            for (int i = startSentence; i <= groupEnd; i++) {
                groupIndexBySentence[i] = curGroup;
            }

            startSentence = endExclusive;
            curGroup++;
        }

        if (embedGroups.size() <= 1) {
            // embedding小段太少，直接按长度合并
            return mergeSentencesByLength(sentences);
        }

        // 2) embedding：按批次调用（DashScope 单次最多 25 条）
        List<float[]> vectors = embedInBatches(embedGroups, 25);
        if (vectors == null || vectors.size() != embedGroups.size()) {
            // 如果 embedding 返回异常，降级成长度合并
            return mergeSentencesByLength(sentences);
        }

        // 3) 计算相邻组的相似度，得到“主题突变”边界
        boolean[] boundaryBeforeGroup = new boolean[embedGroups.size()];
        boundaryBeforeGroup[0] = false;

        for (int i = 1; i < embedGroups.size(); i++) {
            double sim = cosineSim(vectors.get(i - 1), vectors.get(i));
            boundaryBeforeGroup[i] = (sim < semanticThreshold);
        }

        // 4) 根据边界，最终合并成 chunk（带 overlap）
        List<String> chunks = new ArrayList<>();

        StringBuilder current = new StringBuilder();
        int currentCp = 0;

        for (int si = 0; si < sentences.size(); si++) {
            String s = sentences.get(si).trim();
            if (s.isEmpty()) continue;

            int g = groupIndexBySentence[si];
            boolean shouldCutHere = (g > 0 && boundaryBeforeGroup[g]);

            int sCp = cpLen(s);

            // 如果到主题边界，且当前块已经足够长 => flush
            if (shouldCutHere && currentCp >= minChunkChars) {
                addChunk(chunks, current.toString());
                // overlap
                String overlap = tailByCodePoints(current.toString(), chunkOverlapChars);
                current.setLength(0);
                current.append(overlap);
                currentCp = cpLen(overlap);
            }

            // 正常追加
            if (current.length() > 0) current.append(' ');
            current.append(s);
            currentCp += sCp + 1; // 近似：加一个空格占用

            // 如果块太大，直接 flush
            if (currentCp >= chunkSizeChars) {
                addChunk(chunks, current.toString());
                String overlap = tailByCodePoints(current.toString(), chunkOverlapChars);
                current.setLength(0);
                current.append(overlap);
                currentCp = cpLen(overlap);
            }
        }

        if (current.length() > 0 && cpLen(current.toString()) >= minChunkChars) {
            addChunk(chunks, current.toString());
        }

        // 最终兜底：避免空
        if (chunks.isEmpty()) {
            return mergeSentencesByLength(sentences);
        }

        return chunks;
    }

    private List<String> splitToSentences(String text) {
        // SENTENCE_SPLIT_AFTER_PUNCT 会在“句末标点之后”断开，因此标点会留在前一个句子中
        String[] parts = SENTENCE_SPLIT_AFTER_PUNCT.split(text);
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            String s = (p == null) ? "" : p.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private void addChunk(List<String> chunks, String chunkText) {
        if (chunkText == null) return;
        String t = chunkText.trim();
        if (t.isEmpty()) return;
        if (cpLen(t) < minChunkChars) return;
        chunks.add(t);
    }

    private List<String> mergeSentencesByLength(List<String> sentences) {
        // embedding失败时的降级方案：按句子长度合并成块
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentCp = 0;

        for (String s : sentences) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;

            int sCp = cpLen(t);

            if (current.length() > 0 && currentCp + sCp + 1 > chunkSizeChars) {
                if (currentCp >= minChunkChars) {
                    chunks.add(current.toString().trim());
                }
                String overlap = tailByCodePoints(current.toString(), chunkOverlapChars);
                current.setLength(0);
                current.append(overlap);
                currentCp = cpLen(overlap);
            }

            if (current.length() > 0) current.append(' ');
            current.append(t);
            currentCp += sCp + 1;
        }

        if (current.length() > 0 && currentCp >= minChunkChars) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private List<String> mergeToChunks(List<String> segments) {
        // 非常保守的兜底：segments本身通常就是整段
        List<String> chunks = new ArrayList<>();
        for (String seg : segments) {
            if (seg == null) continue;
            String t = seg.trim();
            if (t.isEmpty()) continue;
            if (cpLen(t) <= chunkSizeChars) {
                if (cpLen(t) >= minChunkChars) chunks.add(t);
            } else {
                // 超长：按 tailByCodePoints 简单硬切（不做语义）
                int startCp = 0;
                while (startCp < cpLen(t)) {
                    int endCp = Math.min(startCp + chunkSizeChars, cpLen(t));
                    int startIdx = t.offsetByCodePoints(0, startCp);
                    int endIdx = t.offsetByCodePoints(0, endCp);
                    String part = t.substring(startIdx, endIdx).trim();
                    if (!part.isEmpty()) chunks.add(part);
                    startCp = endCp - chunkOverlapChars;
                    if (startCp < 0) startCp = 0;
                }
            }
        }
        return chunks;
    }

    private static int cpLen(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    private static String tailByCodePoints(String text, int maxCp) {
        if (text == null) return "";
        if (maxCp <= 0) return "";
        int totalCp = text.codePointCount(0, text.length());
        if (totalCp <= maxCp) return text.trim();
        int startCp = totalCp - maxCp;
        int startIdx = text.offsetByCodePoints(0, startCp);
        return text.substring(startIdx).trim();
    }

    private static double cosineSim(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return 0.0;
        if (a.length != b.length) return 0.0;

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB) + 1e-9;
        return dot / denom;
    }

    /**
     * 分批调用 embedding，避免 DashScope 单次 texts 超过 25 的限制。
     *
     * @param texts 待 embedding 文本
     * @param batchSize 每批大小（DashScope 建议 <= 25）
     * @return 与 texts 一一对应的向量列表；若任何一批异常返回 null
     */
    private List<float[]> embedInBatches(List<String> texts, int batchSize) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须为正数");
        }
        List<float[]> all = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            List<String> batch = texts.subList(start, end);
            List<float[]> vectors;
            try {
                vectors = embeddingModel.embed(batch);
            } catch (Exception ex) {
                // 这里不抛异常，返回 null 让上层走降级切分，保证服务可用性
                log.warn("Embedding batch failed, start={}, end={}, msg={}", start, end, ex.getMessage());
                return null;
            }
            // 防御校验：保证返回数量与输入批次一致
            if (vectors == null || vectors.size() != batch.size()) {
                return null;
            }
            all.addAll(vectors);
        }
        return all;
    }
}