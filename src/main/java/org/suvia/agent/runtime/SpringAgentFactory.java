package org.suvia.agent.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.suvia.agent.BaseAgent;
import org.suvia.agent.MyManus;
import org.suvia.agent.intent.CapabilityToolRouter;

@Component
public class SpringAgentFactory implements AgentFactory {

    private final ObjectProvider<MyManus> provider;
    private final CapabilityToolRouter toolRouter;

    public SpringAgentFactory(ObjectProvider<MyManus> provider, CapabilityToolRouter toolRouter) {
        this.provider = provider;
        this.toolRouter = toolRouter;
    }

    @Override
    public BaseAgent create(org.suvia.agent.intent.TaskSpec taskSpec) {
        MyManus agent = provider.getObject();
        agent.configureFor(taskSpec, toolRouter.select(agent.getAvailableTools(), taskSpec));
        return agent;
    }
}
