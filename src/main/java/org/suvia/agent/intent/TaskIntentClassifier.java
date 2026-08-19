package org.suvia.agent.intent;

@FunctionalInterface
public interface TaskIntentClassifier {
    TaskSpec classify(String request);
}
