package org.suvia.chatMemory;

import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 每个 chatId 对应一个状态文件：
 * - summary：滚动摘要（几百字）
 * - recentMessages：仅保留最近若干条消息（避免文件无限增大）
 */
public class ConversationState {
    public long version = 0;
    public String summary = "";
    public List<Message> recentMessages = new ArrayList<>();

    public void normalize() {
        if (summary == null) {
            summary = "";
        }
        if (recentMessages == null) {
            recentMessages = new ArrayList<>();
        }
        if (version < 0) {
            throw new MemoryPersistenceException("Conversation memory has an invalid version");
        }
    }
}
