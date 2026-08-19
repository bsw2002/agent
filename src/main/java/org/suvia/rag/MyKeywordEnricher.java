package org.suvia.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;
import org.suvia.trace.ModelCallScene;
import org.suvia.trace.ModelTraceContext;

import java.util.List;

@Component
public class MyKeywordEnricher {
    @Resource
    private ChatModel chatModel;

    public List<Document> enrichDocuments(List<Document> documents) {
        KeywordMetadataEnricher enricher = new KeywordMetadataEnricher(this.chatModel, 5);
        try (ModelTraceContext.Scope ignored = ModelTraceContext.openScene(ModelCallScene.KEYWORD_ENRICHMENT)) {
            return enricher.apply(documents);
        }
    }
}
