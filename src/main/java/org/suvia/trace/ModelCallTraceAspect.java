package org.suvia.trace;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.suvia.security.RequestIdentity;
import org.suvia.security.RequestIdentityResolver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@Slf4j
public class ModelCallTraceAspect {

    private final ModelTraceRepository repository;
    private final RequestIdentityResolver identityResolver;
    private final boolean enabled;
    private final boolean captureContent;
    private final int maxContentCharacters;
    private final String configuredChatModel;
    private final String configuredEmbeddingModel;

    public ModelCallTraceAspect(
            ModelTraceRepository repository,
            RequestIdentityResolver identityResolver,
            @Value("${suvia.model-trace.enabled:true}") boolean enabled,
            @Value("${suvia.model-trace.capture-content:true}") boolean captureContent,
            @Value("${suvia.model-trace.max-content-characters:4000}") int maxContentCharacters,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String configuredChatModel,
            @Value("${spring.ai.openai.embedding.options.model:unknown}") String configuredEmbeddingModel
    ) {
        this.repository = repository;
        this.identityResolver = identityResolver;
        this.enabled = enabled;
        this.captureContent = captureContent;
        this.maxContentCharacters = Math.max(256, maxContentCharacters);
        this.configuredChatModel = configuredChatModel;
        this.configuredEmbeddingModel = configuredEmbeddingModel;
    }

    @Around("execution(* org.springframework.ai.chat.model.ChatModel.call(org.springframework.ai.chat.prompt.Prompt)) && args(prompt)")
    public Object traceChatCall(ProceedingJoinPoint joinPoint, Prompt prompt) throws Throwable {
        if (!enabled) {
            return joinPoint.proceed();
        }
        Instant startedAt = Instant.now();
        ModelTraceContext.Snapshot trace = snapshot(ModelCallScene.DIRECT_CHAT);
        String input = inputText(prompt);
        try {
            ChatResponse response = (ChatResponse) joinPoint.proceed();
            persistChat(trace, ModelCallType.CHAT, input, response, null, ModelCallStatus.SUCCESS, startedAt);
            return response;
        } catch (Throwable error) {
            persistChat(trace, ModelCallType.CHAT, input, null, error, ModelCallStatus.FAILED, startedAt);
            throw error;
        }
    }

    @Around("execution(* org.springframework.ai.chat.model.ChatModel.stream(org.springframework.ai.chat.prompt.Prompt)) && args(prompt)")
    @SuppressWarnings("unchecked")
    public Object traceChatStream(ProceedingJoinPoint joinPoint, Prompt prompt) throws Throwable {
        if (!enabled) {
            return joinPoint.proceed();
        }
        Flux<ChatResponse> source = (Flux<ChatResponse>) joinPoint.proceed();
        ModelTraceContext.Snapshot trace = snapshot(ModelCallScene.DIRECT_CHAT);
        String input = inputText(prompt);
        return Flux.defer(() -> {
            Instant startedAt = Instant.now();
            AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean saved = new AtomicBoolean(false);
            StringBuilder completion = new StringBuilder();
            return source
                    .doOnNext(response -> {
                        lastResponse.set(response);
                        String text = responseText(response);
                        if (text != null && completion.length() < maxContentCharacters) {
                            completion.append(text, 0, Math.min(text.length(), maxContentCharacters - completion.length()));
                        }
                    })
                    .doOnError(failure::set)
                    .doFinally(signal -> {
                        if (!saved.compareAndSet(false, true)) {
                            return;
                        }
                        Throwable error = failure.get();
                        ModelCallStatus status = error != null
                                ? ModelCallStatus.FAILED
                                : signal == SignalType.CANCEL
                                        ? ModelCallStatus.CANCELLED
                                        : ModelCallStatus.SUCCESS;
                        persistChat(
                                trace,
                                ModelCallType.CHAT_STREAM,
                                input,
                                lastResponse.get(),
                                completion.toString(),
                                error,
                                status,
                                startedAt
                        );
                    });
        });
    }

    @Around("execution(* org.springframework.ai.embedding.EmbeddingModel.call(org.springframework.ai.embedding.EmbeddingRequest)) && args(request)")
    public Object traceEmbeddingCall(ProceedingJoinPoint joinPoint, EmbeddingRequest request) throws Throwable {
        if (!enabled) {
            return joinPoint.proceed();
        }
        Instant startedAt = Instant.now();
        ModelTraceContext.Snapshot trace = snapshot(ModelCallScene.EMBEDDING);
        String input = request == null || request.getInstructions() == null
                ? ""
                : String.join("\n", request.getInstructions());
        try {
            EmbeddingResponse response = (EmbeddingResponse) joinPoint.proceed();
            persistEmbedding(trace, input, response, null, startedAt);
            return response;
        } catch (Throwable error) {
            persistEmbedding(trace, input, null, error, startedAt);
            throw error;
        }
    }

    private void persistChat(
            ModelTraceContext.Snapshot trace,
            ModelCallType type,
            String input,
            ChatResponse response,
            Throwable error,
            ModelCallStatus status,
            Instant startedAt
    ) {
        persistChat(trace, type, input, response, null, error, status, startedAt);
    }

    private void persistChat(
            ModelTraceContext.Snapshot trace,
            ModelCallType type,
            String input,
            ChatResponse response,
            String streamedOutput,
            Throwable error,
            ModelCallStatus status,
            Instant startedAt
    ) {
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        String model = response != null && response.getMetadata() != null && response.getMetadata().getModel() != null
                ? response.getMetadata().getModel()
                : configuredChatModel;
        String output = streamedOutput != null && !streamedOutput.isBlank() ? streamedOutput : responseText(response);
        save(new ModelCallRecord(
                UUID.randomUUID(), trace.traceId(), trace.runId(), trace.tenantId(), trace.userId(),
                trace.publicChatId(), trace.scene(), type, model, status,
                sha256(input), preview(input), preview(output), null,
                usage == null ? null : longValue(usage.getPromptTokens()),
                usage == null ? null : longValue(usage.getCompletionTokens()),
                usage == null ? null : longValue(usage.getTotalTokens()),
                elapsed(startedAt), errorCode(error), safeError(error), startedAt, Instant.now()
        ));
    }

    private void persistEmbedding(
            ModelTraceContext.Snapshot trace,
            String input,
            EmbeddingResponse response,
            Throwable error,
            Instant startedAt
    ) {
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        String model = response != null && response.getMetadata() != null && response.getMetadata().getModel() != null
                ? response.getMetadata().getModel()
                : configuredEmbeddingModel;
        Integer dimension = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? null
                : response.getResult().getOutput().length;
        save(new ModelCallRecord(
                UUID.randomUUID(), trace.traceId(), trace.runId(), trace.tenantId(), trace.userId(),
                trace.publicChatId(), ModelCallScene.EMBEDDING, ModelCallType.EMBEDDING, model,
                error == null ? ModelCallStatus.SUCCESS : ModelCallStatus.FAILED,
                sha256(input), null, null, dimension,
                usage == null ? null : longValue(usage.getPromptTokens()), null,
                usage == null ? null : longValue(usage.getTotalTokens()),
                elapsed(startedAt), errorCode(error), safeError(error), startedAt, Instant.now()
        ));
    }

    private ModelTraceContext.Snapshot snapshot(ModelCallScene defaultScene) {
        RequestIdentity identity;
        try {
            identity = identityResolver.resolve(SecurityContextHolder.getContext().getAuthentication());
        } catch (RuntimeException ignored) {
            identity = new RequestIdentity("system", "background");
        }
        return ModelTraceContext.snapshot(identity.tenantId(), identity.userId(), defaultScene);
    }

    private String inputText(Prompt prompt) {
        if (prompt == null) {
            return "";
        }
        if (prompt.getUserMessage() != null && prompt.getUserMessage().getText() != null) {
            return prompt.getUserMessage().getText();
        }
        return prompt.getLastUserOrToolResponseMessage() == null
                ? ""
                : prompt.getLastUserOrToolResponseMessage().getText();
    }

    private String responseText(ChatResponse response) {
        return response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? null
                : response.getResult().getOutput().getText();
    }

    private String preview(String content) {
        if (!captureContent || content == null) {
            return null;
        }
        String redacted = content
                .replaceAll("(?i)(api[-_ ]?key|password|secret)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]")
                .replaceAll("sk-[A-Za-z0-9_-]{8,}", "sk-[REDACTED]");
        return redacted.length() <= maxContentCharacters
                ? redacted
                : redacted.substring(0, maxContentCharacters) + "...[truncated]";
    }

    private String safeError(Throwable error) {
        if (error == null) {
            return null;
        }
        String text = preview(error.getMessage());
        return text == null || text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private String errorCode(Throwable error) {
        return error == null ? null : error.getClass().getSimpleName();
    }

    private void save(ModelCallRecord record) {
        try {
            repository.saveCall(record);
        } catch (RuntimeException persistenceError) {
            log.warn("Failed to persist model call trace: traceId={}, callId={}, errorType={}",
                    record.traceId(), record.callId(), persistenceError.getClass().getSimpleName());
            log.debug("Model call trace persistence failure", persistenceError);
        }
    }

    private long elapsed(Instant startedAt) {
        return Math.max(0, Duration.between(startedAt, Instant.now()).toMillis());
    }

    private Long longValue(Number value) {
        return value == null ? null : value.longValue();
    }

    private String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
