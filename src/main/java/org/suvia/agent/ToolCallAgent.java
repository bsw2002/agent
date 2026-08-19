package org.suvia.agent;

import cn.hutool.core.collection.CollUtil;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.suvia.agent.exception.AgentExecutionException;
import org.suvia.trace.ModelCallScene;
import org.suvia.trace.ModelTraceContext;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@EqualsAndHashCode(callSuper = true)
@Slf4j
public class ToolCallAgent extends ReActAgent {

    private ToolCallback[] availableTools;
    private ChatResponse toolCallChatResponse;
    private final ToolCallingManager toolCallingManager;
    private final ChatOptions chatOptions;

    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        setAvailableTools(availableTools);
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.chatOptions = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
    }

    public ToolCallback[] getAvailableTools() {
        return availableTools.clone();
    }

    public void setAvailableTools(ToolCallback[] availableTools) {
        this.availableTools = availableTools == null ? new ToolCallback[0] : availableTools.clone();
    }

    @Override
    public boolean think() {
        List<Message> messages = getMessageList();
        Prompt prompt = new Prompt(messages, chatOptions);
        try {
            String effectiveSystemPrompt = getSystemPrompt();
            if (getNextStepPrompt() != null && !getNextStepPrompt().isBlank()) {
                effectiveSystemPrompt += "\n\nExecution guidance:\n" + getNextStepPrompt();
            }

            ChatClient.ChatClientRequestSpec request = getChatClient().prompt(prompt)
                    .system(effectiveSystemPrompt);
            if (availableTools.length > 0) {
                request.toolCallbacks(availableTools);
            }
            ChatResponse response;
            try (ModelTraceContext.Scope ignored = ModelTraceContext.openScene(ModelCallScene.AGENT_THINK)) {
                response = request.call().chatResponse();
            }
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                throw new AgentExecutionException("Model returned an empty response");
            }
            this.toolCallChatResponse = response;
            AssistantMessage assistantMessage = response.getResult().getOutput();
            String text = assistantMessage.getText();
            List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls() == null
                    ? Collections.emptyList()
                    : assistantMessage.getToolCalls();

            log.info(
                    "{} model response received: textCharacters={}, toolCallCount={}",
                    getName(),
                    text == null ? 0 : text.length(),
                    toolCalls.size()
            );
            if (!toolCalls.isEmpty()) {
                log.info(
                        "{} selected tools: {}",
                        getName(),
                        toolCalls.stream().map(AssistantMessage.ToolCall::name).collect(Collectors.joining(","))
                );
                return true;
            }

            getMessageList().add(assistantMessage);
            setFinalOutput(text == null ? "" : text);
            return false;
        } catch (AgentExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentExecutionException(getName() + " failed while requesting the model", e);
        }
    }

    @Override
    public String act() {
        if (toolCallChatResponse == null || !toolCallChatResponse.hasToolCalls()) {
            return "No tool call was requested";
        }

        Prompt prompt = new Prompt(getMessageList(), chatOptions);
        ToolExecutionResult execution = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        setMessageList(execution.conversationHistory());

        Object lastMessage = CollUtil.getLast(execution.conversationHistory());
        if (!(lastMessage instanceof ToolResponseMessage toolResponse)) {
            throw new AgentExecutionException("Tool execution did not return a tool response message");
        }
        log.info("{} tool calls completed: count={}", getName(), toolResponse.getResponses().size());
        return toolResponse.getResponses().stream()
                .map(response -> "Tool " + response.name() + " completed")
                .collect(Collectors.joining("\n"));
    }
}
