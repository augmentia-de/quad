package de.augmentia.quad.core.observability;

import de.augmentia.quad.core.events.AgentEvent;

@FunctionalInterface
public interface AgentEventFilter {
    boolean matches(AgentEvent event);
}
