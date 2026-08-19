package org.suvia.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.suvia.agent.model.AgentState;

/*
* ReAct(Reasoning and Action)模式的代理抽象类
* 实现思考-行动的循环模式
* */
@EqualsAndHashCode(callSuper = true)
@Data
public abstract class ReActAgent extends BaseAgent {  
  
    /*
    * @Return true: 思考完成，可以进行行动
    * */
    public abstract boolean think();  
  
    /*
    * @Return 动作结果
    * */
    public abstract String act();  
  
      
    @Override  
    public String step() {  
        boolean shouldAct = think();
        if (!shouldAct) {
            setState(AgentState.FINISHED);
            return getFinalOutput() == null ? "" : getFinalOutput();
        }

        String actionResult = act();
        return actionResult == null ? "" : actionResult;
    }  
}
