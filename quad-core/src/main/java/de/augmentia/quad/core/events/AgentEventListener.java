package de.augmentia.quad.core.events;

/**
 * Functional interface for consuming agent lifecycle events.
 */
@FunctionalInterface
public interface AgentEventListener {
    void onEvent(AgentEvent event);
}
