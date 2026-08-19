package org.suvia.agent.intent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import org.suvia.trace.ModelCallScene;
import org.suvia.trace.ModelTraceContext;

/**
 * Spring AI equivalent of diet-agent's IntentAgent. It performs one structured
 * model call and never answers the user or invokes tools.
 */
@Component
public class LlmTaskIntentClassifier {

    private static final String SYSTEM_PROMPT = """
            You classify requests for a research assistant. Do not answer the request.
            Return only the structured object requested by the response schema.

            Allowed intents:
            CONVERSATION, KNOWLEDGE_QUESTION, WEB_RESEARCH, FILE_TASK,
            DOCUMENT_GENERATION, CODE_EXECUTION, COMPOSITE_TASK.

            Allowed capabilities:
            MODEL_REASONING, KNOWLEDGE_RETRIEVAL, WEB_SEARCH, WEB_FETCH,
            FILE_READ, FILE_WRITE, RESOURCE_DOWNLOAD, PDF_READ, PDF_WRITE,
            TERMINAL_EXECUTION.

            Allowed risk levels:
            READ_ONLY, WORKSPACE_WRITE, PRIVILEGED_EXECUTION.

            Classification rules:
            - Always include MODEL_REASONING.
            - Knowledge-base or uploaded-paper questions require KNOWLEDGE_RETRIEVAL.
            - Explicit current/latest/web research requires WEB_SEARCH.
            - Reading or analysing a local file requires FILE_READ or PDF_READ.
            - Writing a file or PDF requires FILE_WRITE or PDF_WRITE.
            - Downloading a resource requires RESOURCE_DOWNLOAD.
            - Executing a command or script requires TERMINAL_EXECUTION.
            - Respect explicit negation such as do not search, write, download, or execute.
            - Set requiresClarification=true when the target, file, document, or requested action is unclear.
            - Use a confidence between 0 and 1.
            """;

    private final ChatClient chatClient;
    private final TaskIntentValidator validator;

    public LlmTaskIntentClassifier(ChatModel chatModel, TaskIntentValidator validator) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
        this.validator = validator;
    }

    public TaskSpec classify(String request) {
        LlmIntentResult raw;
        try (ModelTraceContext.Scope ignored = ModelTraceContext.openScene(ModelCallScene.INTENT_CLASSIFICATION)) {
            raw = chatClient.prompt()
                    .user(request)
                    .call()
                    .entity(LlmIntentResult.class);
        }
        return validator.validate(raw);
    }
}
