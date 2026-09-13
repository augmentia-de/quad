package de.augmentia.quad.quarkus.permission;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.augmentia.quad.core.guards.PermissionMode;

/**
 * Per-session {@link PermissionMode} registry (default INTERACTIVE), which the
 * {@link PermissionGuardHook} resolves when guarding tool calls.
 */
public class SessionPermissionRegistry {

    private final Map<String, PermissionMode> modes = new ConcurrentHashMap<>();

    public PermissionMode modeFor(String sessionId) {
        return modes.getOrDefault(sessionId, PermissionMode.INTERACTIVE);
    }

    public void setMode(String sessionId, PermissionMode mode) {
        if (mode == null) {
            modes.remove(sessionId);
        } else {
            modes.put(sessionId, mode);
        }
    }

    public void reset(String sessionId) {
        modes.remove(sessionId);
    }

    public Map<String, PermissionMode> allModes() {
        return Map.copyOf(modes);
    }
}
