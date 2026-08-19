package org.suvia.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.suvia.common.BaseResponse;
import org.suvia.common.ResultUtils;
import org.suvia.controller.dto.ExplicitMemoryRequest;
import org.suvia.controller.dto.MemoryView;
import org.suvia.memory.LongTermMemoryService;
import org.suvia.memory.MemoryScope;
import org.suvia.security.ConversationKeyFactory;
import org.suvia.security.RequestIdentity;
import org.suvia.security.RequestIdentityResolver;

import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/ai/memories")
public class MemoryController {

    private final LongTermMemoryService memories;
    private final RequestIdentityResolver identityResolver;
    private final ConversationKeyFactory conversationKeys;

    public MemoryController(
            LongTermMemoryService memories,
            RequestIdentityResolver identityResolver,
            ConversationKeyFactory conversationKeys
    ) {
        this.memories = memories;
        this.identityResolver = identityResolver;
        this.conversationKeys = conversationKeys;
    }

    @PostMapping
    public BaseResponse<MemoryView> remember(
            @Valid @RequestBody ExplicitMemoryRequest request,
            Authentication authentication
    ) {
        RequestIdentity identity = identityResolver.resolve(authentication);
        MemoryScope scope = request.scope() == null ? MemoryScope.USER : request.scope();
        String scopeKey = resolveScopeKey(scope, request.chatId(), identity);
        return ResultUtils.success(MemoryView.from(memories.rememberExplicit(
                identity,
                scope,
                scopeKey,
                request.content(),
                request.sensitivity()
        )));
    }

    @GetMapping
    public BaseResponse<List<MemoryView>> recall(
            @RequestParam(required = false) String chatId,
            @RequestParam(required = false, defaultValue = "") String query,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit,
            Authentication authentication
    ) {
        RequestIdentity identity = identityResolver.resolve(authentication);
        String scopeKey = chatId == null || chatId.isBlank()
                ? null
                : conversationKeys.scope(identity, chatId).storageConversationId();
        List<MemoryView> result = memories.recall(identity, scopeKey, query, limit)
                .stream()
                .map(MemoryView::from)
                .toList();
        return ResultUtils.success(result);
    }

    @DeleteMapping("/{memoryId}")
    public BaseResponse<Boolean> forget(
            @PathVariable UUID memoryId,
            Authentication authentication
    ) {
        memories.forget(identityResolver.resolve(authentication), memoryId);
        return ResultUtils.success(true);
    }

    private String resolveScopeKey(MemoryScope scope, String chatId, RequestIdentity identity) {
        if (scope == MemoryScope.USER) {
            return "";
        }
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId is required for conversation-scoped memory");
        }
        return conversationKeys.scope(identity, chatId).storageConversationId();
    }
}
