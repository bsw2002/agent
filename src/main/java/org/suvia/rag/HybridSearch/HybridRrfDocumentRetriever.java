package org.suvia.rag.HybridSearch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HybridRrfDocumentRetriever implements DocumentRetriever {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final HybridSearchProperties props;
    private final String keywordSql;

    public HybridRrfDocumentRetriever(
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            HybridSearchProperties props
    ) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.props = props;
        String qualified = quoteQualifiedTable(props.getSchemaName(), props.getTableName());
        this.keywordSql = """
                SELECT id, content, metadata
                FROM %s
                WHERE (content ILIKE ? ESCAPE '\\')
                   OR (CAST(metadata AS TEXT) ILIKE ? ESCAPE '\\')
                LIMIT ?
                """.formatted(qualified);
    }

    private static String quoteQualifiedTable(String schema, String table) {
        return "\"" + schema.replace("\"", "") + "\".\"" + table.replace("\"", "") + "\"";
    }

    @Override
    public List<Document> retrieve(Query query) {
        String text = query == null ? "" : query.text();
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<Document> vectorHits = vectorSearch(text);
        List<Document> keywordHits = keywordSearch(text);

        return RrfMergeUtil.fuse(
                List.of(vectorHits, keywordHits),
                props.getRrfK(),
                props.getRrfOutputTopK()
        );
    }

    private List<Document> vectorSearch(String text) {
        SearchRequest.Builder b = SearchRequest.builder()
                .query(text)
                .topK(Math.max(1, props.getVectorTopK()));
        if (props.getSimilarityThreshold() > 0) {
            b.similarityThreshold(props.getSimilarityThreshold());
        }
        List<Document> found = vectorStore.similaritySearch(b.build());
        return found != null ? found : List.of();
    }

    private List<Document> keywordSearch(String text) {
        String escaped = escapeLikePattern(text.trim());
        if (escaped.isEmpty()) {
            return List.of();
        }
        String pattern = "%" + escaped + "%";
        int limit = Math.max(1, props.getKeywordTopK());

        return jdbcTemplate.query(
                keywordSql,
                (rs, rowNum) -> {
                    String id = rs.getString("id");
                    String content = rs.getString("content");
                    Map<String, Object> meta = parseMetadata(rs.getString("metadata"));
                    return Document.builder()
                            .id(id)
                            .text(content != null ? content : "")
                            .metadata(meta)
                            .build();
                },
                pattern,
                pattern,
                limit
        );
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    static String escapeLikePattern(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.length() > 800 ? raw.substring(0, 800) : raw;
        return s.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}