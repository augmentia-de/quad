package de.augmentia.quad.quarkus.security;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * JAX-RS request filter that enforces OIDC authentication only when
 * {@code quad.security.oidc.enabled=true}.
 * <p>
 * When disabled (default), all requests pass through without auth checks.
 * When enabled, requests without a valid Bearer token are rejected with 401.
 * <p>
 * This replaces @Authenticated for opt-in security. Role-based authorization
 * is handled programmatically via {@code SecurityContext.hasRole()}.
 */
@Provider
@PreMatching
public class SecurityEnforcementFilter implements ContainerRequestFilter {

    private static final Logger log = Logger.getLogger(SecurityEnforcementFilter.class);

    private final boolean oidcEnabled;

    public SecurityEnforcementFilter(
            @ConfigProperty(name = "quad.security.oidc.enabled", defaultValue = "false") boolean oidcEnabled) {
        this.oidcEnabled = oidcEnabled;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!oidcEnabled) return;

        String path = requestContext.getUriInfo().getPath();

        // Allow health checks and openapi without auth
        if (path.startsWith("/q/") || path.startsWith("/health") || path.startsWith("/openapi")) {
            return;
        }

        String authHeader = requestContext.getHeaderString("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debugf("Rejecting unauthenticated request to %s", path);
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .entity("{\"error\":\"Missing or invalid Authorization header\"}")
                .build());
        }
    }
}
