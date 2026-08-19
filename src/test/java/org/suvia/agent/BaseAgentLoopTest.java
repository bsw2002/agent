package org.suvia.agent;

import org.junit.jupiter.api.Test;
import org.suvia.agent.exception.AgentExecutionException;
import org.suvia.agent.exception.AgentMaxStepsExceededException;
import org.suvia.agent.model.AgentState;

import java.util.ArrayDeque;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BaseAgentLoopTest {

    @Test
    void returnsTheModelFinalAnswerInsteadOfStepTrace() {
        FakeAgent agent = new FakeAgent(true, false);
        agent.setFinalAnswerOnCompletion("final answer");

        String result = agent.run("do the task");

        assertEquals("final answer", result);
        assertEquals(AgentState.FINISHED, agent.getState());
        assertEquals(2, agent.thinkCalls);
        assertEquals(1, agent.actCalls);
    }

    @Test
    void completesImmediatelyWhenNoToolIsNeeded() {
        FakeAgent agent = new FakeAgent(false);
        agent.setFinalAnswerOnCompletion("direct answer");

        assertEquals("direct answer", agent.run("answer directly"));
        assertEquals(1, agent.thinkCalls);
        assertEquals(0, agent.actCalls);
    }

    @Test
    void reportsMaxStepsAsFailureInsteadOfSuccess() {
        FakeAgent agent = new FakeAgent(true, true, true);
        agent.setMaxSteps(2);

        assertThrows(AgentMaxStepsExceededException.class, () -> agent.run("never finish"));
        assertEquals(AgentState.ERROR, agent.getState());
    }

    @Test
    void propagatesExecutionFailures() {
        ReActAgent agent = new ReActAgent() {
            @Override
            public boolean think() {
                throw new IllegalStateException("model unavailable");
            }

            @Override
            public String act() {
                return "unused";
            }
        };

        AgentExecutionException error = assertThrows(
                AgentExecutionException.class,
                () -> agent.run("test failure")
        );

        assertEquals("Agent execution failed", error.getMessage());
        assertEquals(AgentState.ERROR, agent.getState());
    }

    private static final class FakeAgent extends ReActAgent {
        private final Queue<Boolean> decisions = new ArrayDeque<>();
        private String finalAnswerOnCompletion = "";
        private int thinkCalls;
        private int actCalls;

        private FakeAgent(Boolean... decisions) {
            this.decisions.addAll(java.util.List.of(decisions));
        }

        @Override
        public boolean think() {
            thinkCalls++;
            boolean shouldAct = decisions.isEmpty() || decisions.remove();
            if (!shouldAct) {
                setFinalOutput(finalAnswerOnCompletion);
            }
            return shouldAct;
        }

        @Override
        public String act() {
            actCalls++;
            return "tool observation";
        }

        private void setFinalAnswerOnCompletion(String value) {
            this.finalAnswerOnCompletion = value;
        }
    }
}
