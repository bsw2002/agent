package org.suvia.agent.runtime;

import org.suvia.agent.BaseAgent;
import org.suvia.agent.intent.TaskSpec;

public interface AgentFactory {
    BaseAgent create(TaskSpec taskSpec);
}
