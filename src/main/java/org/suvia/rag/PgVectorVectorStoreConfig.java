package org.suvia.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.suvia.rag.service.AppDocumentLoader;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

/**
 * PgVector 向量库。通过 {@code suvia.rag.ingest-only-if-empty} 控制：默认仅在表为空时执行入库，
 * 避免每次启动/测试重复追加相同文档。
 */
@Configuration
@Slf4j
public class PgVectorVectorStoreConfig {

    private static final String VECTOR_COUNT_SQL = "SELECT COUNT(*) FROM public.vector_store";

    @Resource
    private AppDocumentLoader appDocumentLoader;

    @Resource
    private MyPagePdfDocumentReader myPagePdfDocumentReader;

    @Resource
    private MyTokenTextSplitter myTokenTextSplitter;

    @Resource
    private MyKeywordEnricher myKeywordEnricher;

    /**
     * 为 true（默认）时，仅当 {@code vector_store} 行数为 0 时才加载 PDF/Markdown 并写入，防止重复入库。
     * 需要重新全量入库时：清空表 {@code TRUNCATE public.vector_store;} 或将本项设为 false（每次启动都会再写一份，慎用）。
     */
    @Value("${suvia.rag.ingest-only-if-empty:true}")
    private boolean ingestOnlyIfEmpty;

    @Bean
    public VectorStore pgVectorVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        VectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1536)
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("vector_store")
                .maxDocumentBatchSize(10000)
                .build();

        boolean shouldIngest = !ingestOnlyIfEmpty || isVectorTableEmpty(jdbcTemplate);
        if (!shouldIngest) {
            log.info("跳过向量入库：ingest-only-if-empty=true 且 public.vector_store 已有数据。若需重新入库请先 TRUNCATE 或关闭该开关。");
            return vectorStore;
        }

        // 1. Markdown（按需取消注释）
        /*
        List<Document> markdownDocs = loveAppDocumentLoader.loadMarkdowns();
        List<Document> enrichedMarkdownDocs = myKeywordEnricher.enrichDocuments(markdownDocs);
        vectorStore.add(enrichedMarkdownDocs);
        */

     /*   // 2. PDF 文献：分割后入库（元数据随块保留）
        List<Document> pdfDocs = myPagePdfDocumentReader.loadPdfsFromClasspath();
        if (!pdfDocs.isEmpty()) {
            List<Document> splitPdfDocs = myTokenTextSplitter.splitDocuments(pdfDocs);
            vectorStore.add(splitPdfDocs);
            log.info("向量入库完成：PDF 切块 {} 条已写入 PgVector。", splitPdfDocs.size());
        }*/

        return vectorStore;
    }

    private boolean isVectorTableEmpty(JdbcTemplate jdbcTemplate) {
        try {
            Long count = jdbcTemplate.queryForObject(VECTOR_COUNT_SQL, Long.class);
            return count == null || count == 0L;
        } catch (Exception e) {
            // 表尚不存在或权限问题时，仍尝试后续 add（initializeSchema 通常已建表）
            log.warn("无法统计 vector_store 行数，将尝试执行入库: {}", e.getMessage());
            return true;
        }
    }
}
