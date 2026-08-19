package org.suvia.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Paid, networked smoke tests for Bailian's OpenAI-compatible API.
 * Run explicitly with SUVIA_RUN_LIVE_TESTS=true.
 */
@SpringBootTest(
        classes = OpenAiCompatibleModelLiveTest.LiveModelTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@EnabledIfEnvironmentVariable(named = "SUVIA_RUN_LIVE_TESTS", matches = "true")
class OpenAiCompatibleModelLiveTest {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Value("${spring.ai.openai.chat.options.model}")
    private String configuredChatModel;

    @Value("${spring.ai.openai.embedding.options.model}")
    private String configuredEmbeddingModel;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void qwenPlusReturnsAChatCompletion() {
        assertEquals("qwen-plus", configuredChatModel);

        ChatResponse response = chatModel.call(new Prompt("只回复：MODEL_OK"));

        assertNotNull(response);
        assertNotNull(response.getResult());
        String text = response.getResult().getOutput().getText();
        assertNotNull(text);
        assertFalse(text.isBlank());
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void textEmbeddingV1ReturnsPgvectorCompatibleDimension() {
        assertEquals("text-embedding-v1", configuredEmbeddingModel);

        float[] vector = embeddingModel.embed("Spring AI 1.1.8 模型连通性测试");

        assertNotNull(vector);
        assertEquals(1536, vector.length);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            PgVectorStoreAutoConfiguration.class
    })
    static class LiveModelTestApplication {
    }
}
