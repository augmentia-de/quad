package de.augmentia.quad.core.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-Memory-Implementierung des {@link SessionManager}.
 * Suitable for tests and single-node deployments.
 */
public class InMemorySessionManager implements SessionManager {

    private final Map<String, AgentSessionState> sessions = new ConcurrentHashMap<>();
    private final ReentrantLock globalLock = new ReentrantLock();

    @Override
    public AgentSessionState createSession() {
        AgentSessionState state = new AgentSessionState();
        sessions.put(state.getSessionId(), state);
        return state;
    }

    @Override
    public AgentSessionState getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    @Override
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }

    @Override
    public <T> T executeLocked(String sessionId, java.util.concurrent.Callable<T> action) throws Exception {
        globalLock.lock();
        try {
            return action.call();
        } finally {
            globalLock.unlock();
        }
    }

    @Override
    public int activeSessionCount() {
        return sessions.size();
    }

    @Override
    public void clear() {
        sessions.clear();
    }
}
