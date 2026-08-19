package org.suvia.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationKeyFactoryTest {

    private final ConversationKeyFactory factory = new ConversationKeyFactory();

    @Test
    void namespacesTheSamePublicChatIdByTenantAndUser() {
        ConversationKeyFactory.ScopedConversation first = factory.scope(
                new RequestIdentity("tenant-a", "user-a"),
                "shared-chat"
        );
        ConversationKeyFactory.ScopedConversation second = factory.scope(
                new RequestIdentity("tenant-a", "user-b"),
                "shared-chat"
        );
        ConversationKeyFactory.ScopedConversation third = factory.scope(
                new RequestIdentity("tenant-b", "user-a"),
                "shared-chat"
        );

        assertEquals("shared-chat", first.publicChatId());
        assertNotEquals(first.storageConversationId(), second.storageConversationId());
        assertNotEquals(first.storageConversationId(), third.storageConversationId());
    }

    @Test
    void producesStableStorageKeysForTheSameIdentity() {
        RequestIdentity identity = new RequestIdentity("tenant", "user");

        String first = factory.scope(identity, "chat-1").storageConversationId();
        String second = factory.scope(identity, "chat-1").storageConversationId();

        assertEquals(first, second);
    }

    @Test
    void rejectsUnsafeChatIdentifiers() {
        RequestIdentity identity = new RequestIdentity("tenant", "user");

        assertThrows(IllegalArgumentException.class, () -> factory.scope(identity, "../../other-user"));
        assertThrows(IllegalArgumentException.class, () -> factory.scope(identity, "contains spaces"));
    }
}
