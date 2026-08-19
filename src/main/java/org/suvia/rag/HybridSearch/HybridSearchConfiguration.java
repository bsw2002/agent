package org.suvia.rag.HybridSearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.suvia.demo.rag.myMultiQueryExpander;

@Configuration
@EnableConfigurationProperties(HybridSearchProperties.class)
public class HybridSearchConfiguration {



    @Bean
    public HybridRrfDocumentRetriever hybridRrfDocumentRetriever(
            VectorStore pgVectorVectorStore,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            HybridSearchProperties hybridSearchProperties
    ) {
        return new HybridRrfDocumentRetriever(pgVectorVectorStore, jdbcTemplate, objectMapper, hybridSearchProperties);
    }

    @Bean
    public Advisor hybridRetrievalAugmentationAdvisor(
            HybridRrfDocumentRetriever hybridRrfDocumentRetriever,
            ObjectProvider<myMultiQueryExpander> multiQueryExpanderProvider,
            HybridSearchProperties hybridSearchProperties
    ) {
        DocumentRetriever retriever = hybridSearchProperties.isMultiQueryEnabled()
                ? new MultiQueryHybridDocumentRetriever(
                        hybridRrfDocumentRetriever,
                        multiQueryExpanderProvider.getObject(),
                        hybridSearchProperties)
                : hybridRrfDocumentRetriever;
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(true)
                        .build())
                .build();
    }
}
