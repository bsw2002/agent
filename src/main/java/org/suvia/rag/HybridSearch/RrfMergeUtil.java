package org.suvia.rag.HybridSearch;

import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RrfMergeUtil {

    private RrfMergeUtil() {
    }

    /**
     * RRF：score(d) = Σ 1/(rrfK + rank)，rank 从 1 开始（列表下标 i 对应 rank = i+1）。
     */
    public static List<Document> fuse(List<List<Document>> rankedLists, int rrfK, int outputTopK) {
        if (rankedLists == null || rankedLists.isEmpty() || outputTopK <= 0) {
            return List.of();
        }
        int k = Math.max(1, rrfK);
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> docById = new HashMap<>();

        for (List<Document> list : rankedLists) {
            if (list == null) {
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                Document d = list.get(i);
                if (d == null || d.getId() == null) {
                    continue;
                }
                String id = d.getId();
                scores.merge(id, 1.0 / (k + i + 1), Double::sum);
                docById.putIfAbsent(id, d);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(outputTopK)
                .map(e -> docById.get(e.getKey()))
                .filter(Objects::nonNull)
                .toList();
    }
}