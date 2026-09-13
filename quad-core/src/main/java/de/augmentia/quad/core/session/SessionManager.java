package de.augmentia.quad.core.session;

/**
 * Abstraction for session management. Concrete implementations:
 * <ul>
 *   <li>{@code InMemorySessionManager} (quad-core) — In-Memory-Map</li>
 * </ul>
 * Plattform-spezifische Stores (JDBC/Redis) implementieren dieses Interface.
 */
public interface SessionManager {

    AgentSessionState createSession();

    AgentSessionState getSession(String sessionId);

    void removeSession(String sessionId);

    <T> T executeLocked(String sessionId, java.util.concurrent.Callable<T> action) throws Exception;

    int activeSessionCount();

    void clear();
}
