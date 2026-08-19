package org.suvia.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class ConversationKeyFactory {

    public ScopedConversation scope(RequestIdentity identity, String requestedChatId) {
        String publicChatId = requestedChatId == null || requestedChatId.isBlank()
                ? UUID.randomUUID().toString()
                : validateChatId(requestedChatId);
        String material = identity.tenantId() + "\u0000" + identity.userId() + "\u0000" + publicChatId;
        return new ScopedConversation(publicChatId, "v1:" + sha256(material));
    }

    private String validateChatId(String chatId) {
        if (chatId.length() > 128 || !chatId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("chatId must contain only letters, numbers, '_' or '-' and be at most 128 characters");
        }
        return chatId;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record ScopedConversation(String publicChatId, String storageConversationId) {
    }
}
