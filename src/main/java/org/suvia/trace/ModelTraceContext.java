package org.suvia.trace;

import reactor.core.publisher.Flux;

import java.util.UUID;

/** Lightweight request context used to correlate all model calls in one request. */
public final class ModelTraceContext {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private ModelTraceContext() {
    }

    public static Scope openTrace(UUID traceId) {
        State previous = CURRENT.get();
        State state = new State(traceId == null ? UUID.randomUUID() : traceId);
        CURRENT.set(state);
        return () -> restore(previous, state);
    }

    public static Scope openScene(ModelCallScene scene) {
        State state = CURRENT.get();
        if (state == null) {
            state = new State(UUID.randomUUID());
            CURRENT.set(state);
        }
        ModelCallScene previous = state.scene;
        State captured = state;
        state.scene = scene == null ? ModelCallScene.UNKNOWN : scene;
        return () -> captured.scene = previous;
    }

    public static void attachConversation(String publicChatId) {
        State state = currentOrCreate();
        state.publicChatId = publicChatId;
    }

    public static void attachRunId(UUID runId) {
        State state = currentOrCreate();
        state.runId = runId;
    }

    public static void attachOwner(String tenantId, String userId) {
        State state = currentOrCreate();
        state.tenantId = tenantId;
        state.userId = userId;
    }

    /**
     * Captures the servlet-thread trace state and restores it while a deferred
     * reactive pipeline is subscribed. This is required because ChatClient
     * performs streaming model invocation lazily, after the controller returns.
     */
    public static <T> Flux<T> propagate(Flux<T> source) {
        State state = CURRENT.get();
        if (state == null) {
            return source;
        }
        State captured = state.copy();
        return Flux.using(
                () -> install(captured),
                ignored -> source,
                Scope::close
        );
    }

    public static Snapshot snapshot(String tenantId, String userId, ModelCallScene defaultScene) {
        State state = CURRENT.get();
        if (state == null) {
            state = new State(UUID.randomUUID());
        }
        ModelCallScene scene = state.scene == null || state.scene == ModelCallScene.UNKNOWN
                ? defaultScene
                : state.scene;
        return new Snapshot(
                state.traceId,
                state.runId,
                state.tenantId == null ? tenantId : state.tenantId,
                state.userId == null ? userId : state.userId,
                state.publicChatId,
                scene == null ? ModelCallScene.UNKNOWN : scene
        );
    }

    private static Scope install(State captured) {
        State previous = CURRENT.get();
        State installed = captured.copy();
        CURRENT.set(installed);
        return () -> restore(previous, installed);
    }

    private static State currentOrCreate() {
        State state = CURRENT.get();
        if (state == null) {
            state = new State(UUID.randomUUID());
            CURRENT.set(state);
        }
        return state;
    }

    private static void restore(State previous, State opened) {
        if (CURRENT.get() != opened) {
            return;
        }
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    private static final class State {
        private final UUID traceId;
        private UUID runId;
        private String publicChatId;
        private String tenantId;
        private String userId;
        private ModelCallScene scene = ModelCallScene.UNKNOWN;

        private State(UUID traceId) {
            this.traceId = traceId;
        }

        private State copy() {
            State copy = new State(traceId);
            copy.runId = runId;
            copy.publicChatId = publicChatId;
            copy.tenantId = tenantId;
            copy.userId = userId;
            copy.scene = scene;
            return copy;
        }
    }

    public record Snapshot(
            UUID traceId,
            UUID runId,
            String tenantId,
            String userId,
            String publicChatId,
            ModelCallScene scene
    ) {
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
