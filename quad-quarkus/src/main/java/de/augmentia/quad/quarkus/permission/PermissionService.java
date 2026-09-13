package de.augmentia.quad.quarkus.permission;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.guards.PermissionEngine;
import de.augmentia.quad.core.guards.PermissionMode;
import de.augmentia.quad.core.hitl.checkpoint.CheckpointService;
import de.augmentia.quad.core.hook.pipeline.HookRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Wires the permission engine + per-session mode registry and lets the
 * {@link PermissionGuardHook} be attached to agents.
 */
@ApplicationScoped
public class PermissionService {

    @ConfigProperty(name = "quad.permission.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "quad.permission.hitl-escalation", defaultValue = "false")
    boolean hitlEscalation;

    private final PermissionEngine engine = new PermissionEngine();
    private final SessionPermissionRegistry registry = new SessionPermissionRegistry();

    private volatile CheckpointService checkpointService;

    public boolean enabled() {
        return enabled;
    }

    public boolean hitlEscalationEnabled() {
        return hitlEscalation;
    }

    public PermissionEngine engine() {
        return engine;
    }

    public SessionPermissionRegistry registry() {
        return registry;
    }

    public PermissionMode modeFor(String sessionId) {
        return registry.modeFor(sessionId);
    }

    public void setMode(String sessionId, PermissionMode mode) {
        registry.setMode(sessionId, mode);
    }

    /**
     * Makes the HITL {@link CheckpointService} available to freshly built permission guards,
     * so that missing permissions can be escalated to a human instead of being hard-blocked
     * (see {@code quad.permission.hitl-escalation}). No-op when the caller has no HITL service.
     */
    public void attachCheckpointService(CheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    /** Attaches a permission guard to an agent (idempotent by name). */
    public void applyGuard(Agent agent) {
        if (agent == null) {
            return;
        }
        HookRegistry hr = agent.getHookRegistry();
        if (hr != null && hr.getHooks().stream().anyMatch(h -> PermissionGuardHook.NAME.equals(h.name()))) {
            return;
        }
        agent.addHook(new PermissionGuardHook(engine, registry, false, checkpointService, hitlEscalation));
    }
}
