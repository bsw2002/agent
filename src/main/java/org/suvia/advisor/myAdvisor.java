package org.suvia.advisor;


import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

@Slf4j
public class myAdvisor implements CallAdvisor, StreamAdvisor {
    public String getName() {
        return this.getClass().getSimpleName();
    }

    public int getOrder() {
        return 0;
    }

    private ChatClientRequest before(ChatClientRequest request) {
        String userText = request.prompt().getUserMessage() == null
                ? null
                : request.prompt().getUserMessage().getText();
        log.info("AI request received: characters={}", userText == null ? 0 : userText.length());
        return request;
    }

    private void observeAfter(ChatClientResponse response) {
        String text = response.chatResponse().getResult().getOutput().getText();
        log.info("AI response received: characters={}", text == null ? 0 : text.length());
    }

    public String toString() {
        return org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor.class.getSimpleName();
    }

    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        request = this.before(request);
        ChatClientResponse response = chain.nextCall(request);
        this.observeAfter(response);
        return response;
    }

    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        request = this.before(request);
        Flux<ChatClientResponse> responses = chain.nextStream(request);
        return new ChatClientMessageAggregator().aggregateChatClientResponse(responses, this::observeAfter);
    }
}
