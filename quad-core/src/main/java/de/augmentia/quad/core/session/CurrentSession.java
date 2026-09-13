package de.augmentia.quad.core.session;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Application-scoped holder for the current {@link AgentSessionState}.
 * <p>
 * Uses a single-thread-safe ThreadLocal as the sole source of truth.
 * The @ApplicationScoped annotation allows injection into any component,
 * including agent threads outside HTTP request context.
 * All reads go through getCurrent()/setCurrent() static methods.
 * This avoids dual-state bugs where separate instances see different values.
 */
@ApplicationScoped
public class CurrentSession {

    // ── Single source of truth: ThreadLocal ──
    private static final ThreadLocal<AgentSessionState> holder = new ThreadLocal<>();

    /** Bind session state — updates the single ThreadLocal source. */
    public void bind(AgentSessionState s) { holder.set(s); }
    
    /** Get current session state — delegates to the ThreadLocal. */
    public AgentSessionState get() { return holder.get(); }

    // ── Convenience API for direct access ──
    public static void setCurrent(AgentSessionState s) { holder.set(s); }
    public static AgentSessionState getCurrent() { return holder.get(); }
}
