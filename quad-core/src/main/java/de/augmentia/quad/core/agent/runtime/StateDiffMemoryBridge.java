package de.augmentia.quad.core.agent.runtime;

import java.util.ArrayList;
import java.util.List;

import de.augmentia.quad.core.session.StateMutationListener;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class StateDiffMemoryBridge implements StateMutationListener {
    private final List<String> diffs = new ArrayList<>();

    @Override
    public void onStateChanged(String sessionId, String field, Object oldValue, Object newValue) {
        diffs.add(field + ": " + oldValue + " -> " + newValue);
    }

    public List<String> getDiffs() {
        return List.copyOf(diffs);
    }

    public String getDiffSummary() {
        if (diffs.isEmpty()) return "No state changes.";
        return "State changes: " + String.join(", ", diffs);
    }

    public void clear() {
        diffs.clear();
    }
}