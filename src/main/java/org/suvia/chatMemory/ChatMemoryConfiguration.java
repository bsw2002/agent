package org.suvia.chatMemory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class ChatMemoryConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "suvia.chatmemory.backend", havingValue = "jdbc", matchIfMissing = true)
    public ChatMemory jdbcSummarizingChatMemory(
            JdbcTemplate jdbcTemplate,
            ConversationSummarizer summarizer,
            @Value("${suvia.chatmemory.max-recent-messages:40}") int maxRecentMessages,
            @Value("${suvia.chatmemory.max-recent-tokens:6000}") int maxRecentTokens,
            @Value("${suvia.chatmemory.summarize-batch-size:10}") int summarizeBatchSize,
            @Value("${suvia.chatmemory.min-recent-messages:4}") int minRecentMessages,
            @Value("${suvia.chatmemory.max-summary-characters:4000}") int maxSummaryCharacters
    ) {
        return new SummarizingJdbcChatMemory(
                jdbcTemplate,
                summarizer,
                maxRecentMessages,
                maxRecentTokens,
                summarizeBatchSize,
                minRecentMessages,
                maxSummaryCharacters
        );
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "suvia.chatmemory.backend", havingValue = "file")
    public ChatMemory fileSummarizingChatMemory(
            ConversationSummarizer summarizer,
            @Value("${suvia.chatmemory.dir:chat-memory}") String directory,
            @Value("${suvia.chatmemory.max-recent-messages:40}") int maxRecentMessages,
            @Value("${suvia.chatmemory.max-recent-tokens:6000}") int maxRecentTokens,
            @Value("${suvia.chatmemory.summarize-batch-size:10}") int summarizeBatchSize,
            @Value("${suvia.chatmemory.min-recent-messages:4}") int minRecentMessages,
            @Value("${suvia.chatmemory.max-summary-characters:4000}") int maxSummaryCharacters
    ) {
        return new SummarizingFileChatMemory(
                directory,
                maxRecentMessages,
                maxRecentTokens,
                summarizeBatchSize,
                minRecentMessages,
                maxSummaryCharacters,
                summarizer
        );
    }
}
