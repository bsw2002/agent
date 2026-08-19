package org.suvia.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.suvia.common.BaseResponse;
import org.suvia.common.ResultUtils;
import org.suvia.controller.dto.ChatRequest;
import org.suvia.controller.dto.TaskExecutionResult;
import org.suvia.orchestration.TaskOrchestrator;
import org.suvia.security.ConversationKeyFactory;
import org.suvia.security.RequestIdentity;
import org.suvia.security.RequestIdentityResolver;
import org.suvia.trace.ModelTraceContext;

@RestController
@RequestMapping("/ai/tasks")
public class TaskController {

    private final TaskOrchestrator orchestrator;
    private final RequestIdentityResolver identityResolver;
    private final ConversationKeyFactory conversationKeys;

    public TaskController(
            TaskOrchestrator orchestrator,
            RequestIdentityResolver identityResolver,
            ConversationKeyFactory conversationKeys
    ) {
        this.orchestrator = orchestrator;
        this.identityResolver = identityResolver;
        this.conversationKeys = conversationKeys;
    }

    @PostMapping
    public BaseResponse<TaskExecutionResult> execute(
            @Valid @RequestBody ChatRequest request,
            Authentication authentication
    ) {
        RequestIdentity identity = identityResolver.resolve(authentication);
        ConversationKeyFactory.ScopedConversation conversation = conversationKeys.scope(identity, request.chatId());
        ModelTraceContext.attachOwner(identity.tenantId(), identity.userId());
        ModelTraceContext.attachConversation(conversation.publicChatId());
        return ResultUtils.success(orchestrator.execute(
                request.message(),
                conversation.publicChatId(),
                conversation.storageConversationId(),
                identity
        ));
    }
}
