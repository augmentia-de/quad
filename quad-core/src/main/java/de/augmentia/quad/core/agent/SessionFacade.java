package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;

/**
 * Manages session binding and CWD (current working directory) delegation.
 * Extracted from Agent.java to separate session concerns from agent orchestration.
 */
public class SessionFacade {

    private final CurrentSession currentSession;

    public SessionFacade(CurrentSession currentSession) {
        this.currentSession = currentSession;
    }

    /**
     * Binds the given session state to the thread-local CurrentSession.
     */
    public void bindSession(AgentSessionState state) {
        if (currentSession != null) {
            currentSession.bind(state);
        } else {
            CurrentSession.setCurrent(state);
        }
    }

    /**
     * Returns the current session state from thread-local or injected CurrentSession.
     */
    public AgentSessionState currentState() {
        if (currentSession != null && currentSession.get() != null) {
            return currentSession.get();
        }
        return CurrentSession.getCurrent();
    }

    public String currentCwd() {
        var state = currentState();
        return state != null ? state.currentCwd() : ".";
    }

    public void setCwd(String cwd) {
        var state = currentState();
        if (state != null) state.setCwd(cwd);
    }

    public void pushCwd(String cwd) {
        var state = currentState();
        if (state != null) state.pushCwd(cwd);
    }

    public void popCwd() {
        var state = currentState();
        if (state != null) state.popCwd();
    }

    public void resetCwd() {
        var state = currentState();
        if (state != null) state.resetCwd();
    }

    public String cwdRoot() {
        var state = currentState();
        return state != null ? state.cwdRoot() : ".";
    }

    public int cwdDepth() {
        var state = currentState();
        return state != null ? state.cwdDepth() : 0;
    }
}
