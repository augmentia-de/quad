package de.augmentia.quad.core.hitl.checkpoint;

/**
 * Reports whether an interactive UI (e.g. an SSE stream) is currently connected
 * for a given session. Used by {@link CheckpointService} to decide whether a
 * checkpoint should be delivered only to the interactive UI or fall back to
 * asynchronous notification channels (email, Kafka, ...).
 */
public interface UiConnectionProvider {

    boolean isUiConnected(String sessionId);

    default boolean anyUiConnected() {
        return false;
    }
}