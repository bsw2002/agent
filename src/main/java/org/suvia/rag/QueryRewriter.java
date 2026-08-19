package org.suvia.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.stereotype.Component;
import org.springframework.ai.chat.model.ChatModel;
import org.suvia.trace.ModelCallScene;
import org.suvia.trace.ModelTraceContext;

@Component
public class QueryRewriter {

    private final QueryTransformer queryTransformer;

    public QueryRewriter(ChatModel chatModel) {
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        
        queryTransformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(builder)
                .build();
    }

    public String doQueryRewrite(String prompt) {
        Query query = new Query(prompt);
        
        Query transformedQuery;
        try (ModelTraceContext.Scope ignored = ModelTraceContext.openScene(ModelCallScene.QUERY_REWRITE)) {
            transformedQuery = queryTransformer.transform(query);
        }
        
        return transformedQuery.text();
    }
}
