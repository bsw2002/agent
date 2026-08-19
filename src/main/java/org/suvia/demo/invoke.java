package org.suvia.demo;

import jakarta.annotation.Resource;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "suvia.demo.invoke-enabled", havingValue = "true")
public class invoke implements CommandLineRunner {

    @Resource
    private ChatModel chatModel;

    @Override
    public void run(String... args) throws Exception {
        AssistantMessage message = chatModel.call(new Prompt("你好，我是SUvia"))
                .getResult()
                .getOutput();
        System.out.println(message.getText());


    }


}
