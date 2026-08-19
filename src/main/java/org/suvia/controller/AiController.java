package org.suvia.controller;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.suvia.agent.runtime.AgentEventRecord;
import org.suvia.agent.runtime.AgentRunCoordinator;
import org.suvia.agent.runtime.AgentRunRecord;
import org.suvia.app.AIApp;
import org.suvia.common.BaseResponse;
import org.suvia.common.ResultUtils;
import org.suvia.controller.dto.AgentRunResult;
import org.suvia.controller.dto.ChatRequest;
import org.suvia.controller.dto.ChatResult;
import org.suvia.controller.dto.ChatStreamEvent;
import org.suvia.security.ConversationKeyFactory;
import org.suvia.security.RequestIdentity;
import org.suvia.security.RequestIdentityResolver;
import org.suvia.trace.ModelTraceContext;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/ai")
public class AiController {

    private final AIApp aiApp;
    private final AgentRunCoordinator agentRuns;
    private final RequestIdentityResolver identityResolver;
    private final ConversationKeyFactory conversationKeys;

    public AiController(
            AIApp aiApp,
            AgentRunCoordinator agentRuns,
            RequestIdentityResolver identityResolver,
            ConversationKeyFactory conversationKeys
    ) {
        this.aiApp = aiApp;
        this.agentRuns = agentRuns;
        this.identityResolver = identityResolver;
        this.conversationKeys = conversationKeys;
    }

    @PostMapping("/chat")
    public BaseResponse<ChatResult> chat(
            @Valid @RequestBody ChatRequest request,
            Authentication authentication
    ) {
        ConversationKeyFactory.ScopedConversation conversation = scopedConversation(request, authentication);
        String content = aiApp.doChat(request.message(), conversation.storageConversationId());
        return ResultUtils.success(new ChatResult(conversation.publicChatId(), content));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> streamChat(
            @Valid @RequestBody ChatRequest request,
            Authentication authentication
    ) {
        ConversationKeyFactory.ScopedConversation conversation = scopedConversation(request, authentication);
        return aiApp.doChatByStream(request.message(), conversation.storageConversationId())
                .map(chunk -> ServerSentEvent.<ChatStreamEvent>builder()
                        .event("token")
                        .data(new ChatStreamEvent("token", conversation.publicChatId(), chunk))
                        .build())
                .concatWithValues(ServerSentEvent.<ChatStreamEvent>builder()
                        .event("complete")
                        .data(new ChatStreamEvent("complete", conversation.publicChatId(), ""))
                        .build());
    }

    @PostMapping("/rag")
    public BaseResponse<ChatResult> ragChat(
            @Valid @RequestBody ChatRequest request,
            Authentication authentication
    ) {
        ConversationKeyFactory.ScopedConversation conversation = scopedConversation(request, authentication);
        String content = aiApp.doChatWithRag(request.message(), conversation.storageConversationId());
        return ResultUtils.success(new ChatResult(conversation.publicChatId(), content));
    }

    @PostMapping("/agent")
    public BaseResponse<AgentRunResult> runAgent(
            @Valid @RequestBody ChatRequest request,
            Authentication authentication
    ) {
        ConversationKeyFactory.ScopedConversation conversation = scopedConversation(request, authentication);
        RequestIdentity identity = identityResolver.resolve(authentication);
        AgentRunRecord run = agentRuns.execute(request.message(), conversation.publicChatId(), identity);
        return ResultUtils.success(toResult(run));
    }

    @GetMapping("/agent/runs/{runId}")
    public BaseResponse<AgentRunResult> getAgentRun(
            @PathVariable UUID runId,
            Authentication authentication
    ) {
        RequestIdentity identity = identityResolver.resolve(authentication);
        return ResultUtils.success(toResult(agentRuns.getOwnedRun(runId, identity)));
    }

    @GetMapping("/agent/runs/{runId}/events")
    public BaseResponse<List<AgentEventRecord>> getAgentRunEvents(
            @PathVariable UUID runId,
            Authentication authentication
    ) {
        RequestIdentity identity = identityResolver.resolve(authentication);
        return ResultUtils.success(agentRuns.getOwnedEvents(runId, identity));
    }

    private ConversationKeyFactory.ScopedConversation scopedConversation(
            ChatRequest request,
            Authentication authentication
    ) {
        RequestIdentity identity = identityResolver.resolve(authentication);
        ConversationKeyFactory.ScopedConversation conversation = conversationKeys.scope(identity, request.chatId());
        ModelTraceContext.attachOwner(identity.tenantId(), identity.userId());
        ModelTraceContext.attachConversation(conversation.publicChatId());
        return conversation;
    }

    private AgentRunResult toResult(AgentRunRecord run) {
        return new AgentRunResult(
                run.runId(),
                run.publicChatId(),
                run.status(),
                run.currentStep(),
                run.finalOutput(),
                run.errorCode()
        );
    }
}
