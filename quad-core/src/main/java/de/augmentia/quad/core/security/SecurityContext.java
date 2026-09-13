package de.augmentia.quad.core.security;

import java.util.Map;
import java.util.Set;

/**
 * Framework-agnostic security context for quad-core.
 * Provides user identity throughout the agent execution pipeline.
 * <p>
 * In Quarkus, this is implemented by {@code QuarkusSecurityContext}
 * which delegates to {@code SecurityIdentity}.
 */
public interface SecurityContext {

    /** Unique user identifier (e.g. email, subject claim). */
    String userId();

    /** Roles assigned to the current user. */
    Set<String> roles();

    /** Session identifier binding this context to an agent session. */
    String sessionId();

    /** Additional claims from the token (e.g. act, aud, iss). */
    Map<String, String> claims();

    /** True if this context represents an authenticated user. */
    boolean isAuthenticated();

    /** Check if user has a specific role. */
    default boolean hasRole(String role) {
        return roles().contains(role);
    }

    /** Check if user has any of the given roles. */
    default boolean hasAnyRole(String... roles) {
        for (String r : roles) {
            if (roles().contains(r)) return true;
        }
        return false;
    }
}
