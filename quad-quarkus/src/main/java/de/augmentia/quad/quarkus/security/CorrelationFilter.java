package de.augmentia.quad.quarkus.security;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.UUID;

/**
 * JAX-RS filter that propagates correlation ID from incoming request header.
 * If no correlation ID is present, generates one.
 * Stores it in the request context for downstream use.
 */
@Provider
public class CorrelationFilter implements ContainerRequestFilter {

    private static final Logger log = Logger.getLogger(CorrelationFilter.class);

    public static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String CORRELATION_PROPERTY = "quad.correlation.id";

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String correlationId = requestContext.getHeaderString(CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        requestContext.setProperty(CORRELATION_PROPERTY, correlationId);
        // Make it available to response headers too
        requestContext.getHeaders().putSingle(CORRELATION_HEADER, correlationId);
    }

    /**
     * Extract correlation ID from request context property.
     */
    public static String fromContext(ContainerRequestContext ctx) {
        Object id = ctx.getProperty(CORRELATION_PROPERTY);
        return id != null ? id.toString() : null;
    }
}
