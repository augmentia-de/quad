package de.augmentia.quad.quarkus.security;

import de.augmentia.quad.core.security.SecurityContext;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Quarkus implementation of SecurityContext.
 * Delegates to Quarkus SecurityIdentity for user identity.
 * <p>
 * When OIDC is disabled (quad.security.oidc.enabled=false), falls back
 * to anonymous identity.
 */
@RequestScoped
public class QuarkusSecurityContext implements SecurityContext {

    private final String userId;
    private final Set<String> roles;
    private final boolean authenticated;
    private final Map<String, String> claims;

    @Inject
    public QuarkusSecurityContext(SecurityIdentity identity) {
        if (identity != null && identity.isAnonymous()) {
            this.userId = "anonymous";
            this.roles = Set.of();
            this.authenticated = false;
            this.claims = Map.of();
        } else if (identity != null && identity.getPrincipal() != null) {
            this.userId = identity.getPrincipal().getName();
            this.roles = identity.getRoles().stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
            this.authenticated = true;
            this.claims = Map.of();
        } else {
            this.userId = "anonymous";
            this.roles = Set.of();
            this.authenticated = false;
            this.claims = Map.of();
        }
    }

    /** Fallback constructor for cases where Identity is not available. */
    public QuarkusSecurityContext(String userId, Set<String> roles, boolean authenticated) {
        this.userId = userId;
        this.roles = Set.copyOf(roles);
        this.authenticated = authenticated;
        this.claims = Map.of();
    }

    @Override
    public String userId() {
        return userId;
    }

    @Override
    public Set<String> roles() {
        return roles;
    }

    @Override
    public String sessionId() {
        return "";
    }

    @Override
    public Map<String, String> claims() {
        return claims;
    }

    @Override
    public boolean isAuthenticated() {
        return authenticated;
    }
}
