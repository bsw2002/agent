package org.suvia.chatMemory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

@FunctionalInterface
public interface ConversationSummarizer {
    String summarize(String previousSummary, List<Message> messagesToSummarize);
}
