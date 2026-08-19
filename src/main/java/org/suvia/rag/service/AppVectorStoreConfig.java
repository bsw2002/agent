package org.suvia.rag.service;

import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.suvia.rag.MyKeywordEnricher;

import java.util.List;

@Configuration
@ConditionalOnProperty(name = "suvia.rag.enable-in-memory-vector-store", havingValue = "true")
public class AppVectorStoreConfig {

    @Resource
    private AppDocumentLoader appDocumentLoader;

    @Resource
    private MyKeywordEnricher myKeywordEnricher;
    
    @Bean
    VectorStore loveAppVectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(embeddingModel)
                .build();
        
        List<Document> documents = appDocumentLoader.loadMarkdowns();
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(documents);
        simpleVectorStore.add(enrichedDocuments);
        //simpleVectorStore.add(documents);
        return simpleVectorStore;
    }
}
