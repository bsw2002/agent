package org.suvia.agent;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.suvia.agent.model.AgentState;
import org.suvia.agent.exception.AgentExecutionException;
import org.suvia.agent.exception.AgentMaxStepsExceededException;
import org.suvia.agent.runtime.AgentStepObserver;

import java.util.ArrayList;
import java.util.List;

/*
*
* */
@Data
@Slf4j
public abstract class BaseAgent {  
  
    
    private String name;  
  
    
    private String systemPrompt;  
    private String nextStepPrompt;  
  
    
    private AgentState state = AgentState.IDLE;
  
    
    private int maxSteps = 10;  
    private int currentStep = 0;

    /**
     * The user-facing answer produced when the model decides that no more tools are needed.
     * Tool observations and internal step descriptions must not be used as the final answer.
     */
    private String finalOutput;

    private AgentStepObserver stepObserver = AgentStepObserver.NOOP;
  
    
    private ChatClient chatClient;
  
    /*
    * 记录消息上下文
    * */
    private List<Message> messageList = new ArrayList<>();
  
      
    public String run(String userPrompt) {  
        if (this.state != AgentState.IDLE) {  
            throw new AgentExecutionException("Cannot run agent from state: " + this.state);
        }  
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new AgentExecutionException("Cannot run agent with empty user prompt");
        }  
        
        state = AgentState.RUNNING;  
        
        messageList.add(new UserMessage(userPrompt));
        
        try {  
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {  
                int stepNumber = i + 1;  
                currentStep = stepNumber;  
                log.info("Executing step " + stepNumber + "/" + maxSteps);  

                stepObserver.onStepStarted(stepNumber, maxSteps);
                String stepResult;
                try {
                    stepResult = step();
                } catch (Exception e) {
                    stepObserver.onStepFailed(stepNumber, e.getClass().getSimpleName());
                    throw e;
                }
                stepObserver.onStepCompleted(stepNumber, state);
                log.debug("Agent step {} completed: {}", stepNumber, stepResult);

                if (state == AgentState.FINISHED) {
                    return finalOutput != null ? finalOutput : stepResult;
                }
            }  
            
            stepObserver.onStepFailed(currentStep, "MAX_STEPS_EXCEEDED");
            throw new AgentMaxStepsExceededException(maxSteps);
        } catch (AgentExecutionException e) {
            state = AgentState.ERROR;
            log.error("Agent execution failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {  
            state = AgentState.ERROR;  
            log.error("Agent execution failed: errorType={}", e.getClass().getSimpleName());
            log.debug("Agent execution failure details", e);
            throw new AgentExecutionException("Agent execution failed", e);
        } finally {  
            
            this.cleanup();  
        }  
    }  
  
    /*
    * 单步骤
    * */
    public abstract String step();  
  
      
    protected void cleanup() {  
        
    }  
}
