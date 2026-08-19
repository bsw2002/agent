package org.suvia.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SUVIA_RUN_LIVE_TESTS", matches = "true")
class AIAppTest {

    @Resource
    private AIApp lapp;
    @Test
    void doChat() {
        String chatId = UUID.randomUUID().toString();


        String message = "你好，我叫什么来着";
        String answer = lapp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithReport() {
        String chatId = UUID.randomUUID().toString();


        String message = "你好，我想让另一半（编程导航）更爱我，但我不知道该怎么做";
        AIApp.AIReport aiReport = lapp.doChatWithReport(message, chatId);
        Assertions.assertNotNull(aiReport);
    }

    @Test
    void doChatWithRag() {
        String chatId = UUID.randomUUID().toString();
        String message = "停电经济损失分为哪几类？";
        String answer =  lapp.doChatWithRag(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithTools() {
        String chatId = UUID.randomUUID().toString();
        String message = "请使用终端执行ls命令，并返回结果";
        String answer = lapp.doChatWithTools(message, chatId);
    }

    @Test
    void doTest () {
        int d = 97;
        System.out.println((char)d);
    }
}
