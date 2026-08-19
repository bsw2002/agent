package org.suvia.demo.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.stereotype.Component;
import org.suvia.trace.ModelCallScene;
import org.suvia.trace.ModelTraceContext;

import java.util.List;

@Component
public class myMultiQueryExpander {

    private ChatClient.Builder chatClientBuilder;

    public myMultiQueryExpander(ChatModel chatModel) {
        this.chatClientBuilder = ChatClient.builder(chatModel);
    }

    public List<Query> expand(Query query) {
        MultiQueryExpander queryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(chatClientBuilder)
                .numberOfQueries(3)
                .build();
        try (ModelTraceContext.Scope ignored = ModelTraceContext.openScene(ModelCallScene.MULTI_QUERY_EXPANSION)) {
            return queryExpander.expand(query);
        }
    }

}
