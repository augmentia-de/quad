package de.augmentia.quad.core.security;

import java.util.Map;
import java.util.Set;

/**
 * No-op security context for development, demo, and testing.
 * Treats all requests as anonymous with full access.
 */
public class NoSecurityContext implements SecurityContext {

    private static final NoSecurityContext INSTANCE = new NoSecurityContext();

    private final String sessionId;

    public NoSecurityContext() {
        this("anonymous");
    }

    public NoSecurityContext(String sessionId) {
        this.sessionId = sessionId;
    }

    public static NoSecurityContext get() {
        return INSTANCE;
    }

    @Override
    public String userId() {
        return "anonymous";
    }

    @Override
    public Set<String> roles() {
        return Set.of("admin");
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public Map<String, String> claims() {
        return Map.of();
    }

    @Override
    public boolean isAuthenticated() {
        return false;
    }
}
