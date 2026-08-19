package org.suvia.app;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.suvia.advisor.myAdvisor;
import org.suvia.rag.QueryRewriter;
import org.suvia.trace.ModelCallScene;
import org.suvia.trace.ModelTraceContext;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@Slf4j
public class AIApp {

    private final ChatClient chatClient;
    private final String systemPrompt;

    public AIApp(
            ChatModel chatModel,
            ChatMemory chatMemory,
            @Value("classpath:prompts/system-prompt.st") org.springframework.core.io.Resource systemPromptResource
    ) throws IOException {
        this.systemPrompt = new String(
                systemPromptResource.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(this.systemPrompt)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), new myAdvisor())
                .build();
    }

    public String doChat(String message, String chatId) {
        ChatResponse response;
        try (ModelTraceContext.Scope ignored = ModelTraceContext.openScene(ModelCallScene.DIRECT_CHAT)) {
            response = basePrompt(message, chatId).call().chatResponse();
        }
        String content = response.getResult().getOutput().getText();
        log.info("Chat completed: responseCharacters={}", length(content));
        return content;
    }

    public Flux<String> doChatByStream(String message, String chatId) {
        try (ModelTraceContext.Scope ignored = ModelTraceContext.openScene(ModelCallScene.DIRECT_CHAT)) {
            return ModelTraceContext.propagate(basePrompt(message, chatId).stream().content());
        }
    }

    record AIReport(String title, List<String> advices) {
    }

    public AIReport doChatWithReport(String message, String chatId) {
        AIReport report;
        try (ModelTraceContext.Scope ignored = ModelTraceContext.openScene(ModelCallScene.REPORT_GENERATION)) {
            report = chatClient.prompt()
                    .system(this.systemPrompt + "\n请输出文献阅读报告，包含标题和核心建议列表。")
                    .user(message)
                    .advisors(spec -> memoryParameters(spec, chatId))
                    .call()
                    .entity(AIReport.class);
        }
        log.info("AI report completed: adviceCount={}",
                report == null || report.advices() == null ? 0 : report.advices().size());
        return report;
    }

    @Resource
    private QueryRewriter queryRewriter;

    @Resource
    private Advisor hybridRetrievalAugmentationAdvisor;

    public String doChatWithRag(String message, String chatId) {
        String rewrittenMessage = queryRewriter.doQueryRewrite(message);
        ChatResponse response;
        try (ModelTraceContext.Scope ignored = ModelTraceContext.openScene(ModelCallScene.RAG_ANSWER)) {
            response = chatClient.prompt()
                    .user(rewrittenMessage)
                    .advisors(spec -> memoryParameters(spec, chatId))
                    .advisors(hybridRetrievalAugmentationAdvisor)
                    .call()
                    .chatResponse();
        }
        String content = response.getResult().getOutput().getText();
        log.info("RAG chat completed: responseCharacters={}", length(content));
        return content;
    }

    @Resource
    private ToolCallback[] allTools;

    public String doChatWithTools(String message, String chatId) {
        ChatResponse response;
        try (ModelTraceContext.Scope ignored = ModelTraceContext.openScene(ModelCallScene.AGENT_THINK)) {
            response = basePrompt(message, chatId)
                    .toolCallbacks(allTools)
                    .call()
                    .chatResponse();
        }
        String content = response.getResult().getOutput().getText();
        log.info("Tool chat completed: responseCharacters={}", length(content));
        return content;
    }

    private ChatClient.ChatClientRequestSpec basePrompt(String message, String chatId) {
        return chatClient.prompt()
                .user(message)
                .advisors(spec -> memoryParameters(spec, chatId));
    }

    private void memoryParameters(ChatClient.AdvisorSpec spec, String chatId) {
        spec.param(ChatMemory.CONVERSATION_ID, chatId);
    }

    private int length(String content) {
        return content == null ? 0 : content.length();
    }
}
