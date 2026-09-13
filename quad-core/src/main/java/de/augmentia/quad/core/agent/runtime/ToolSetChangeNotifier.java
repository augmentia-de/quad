package de.augmentia.quad.core.agent.runtime;

import de.augmentia.quad.core.ToolSetChangedEvent;
import de.augmentia.quad.core.session.AgentSessionState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class ToolSetChangeNotifier {
    private java.util.Set<String> lastNotified = java.util.Set.of();

    public void onBeforeModelCall(AgentSessionState state) {
        java.util.Set<String> currentNames = state.getLastToolNames();
        if (currentNames != null && !currentNames.equals(lastNotified)) {
            java.util.Set<String> added = new java.util.HashSet<>(currentNames);
            added.removeAll(lastNotified);
            java.util.Set<String> removed = new java.util.HashSet<>(lastNotified);
            removed.removeAll(currentNames);
            lastNotified = java.util.Set.copyOf(currentNames);
        }
    }

    public void onToolSetChanged(@Observes ToolSetChangedEvent event) {
        lastNotified = java.util.Set.of();
    }
}