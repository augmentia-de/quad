package de.augmentia.quad.quarkus.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST for structured audit (Port 03).
 */
@ApplicationScoped
@Path("/api/audit")
@Produces(MediaType.APPLICATION_JSON)
public class AuditResource {

    @Inject
    DbAuditLogger audit;

    @GET
    public Response query(@QueryParam("limit") int limit,
                          @QueryParam("session") String sessionId,
                          @QueryParam("eventType") String eventType) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", audit.query(limit <= 0 ? 200 : limit, sessionId, eventType));
        return Response.ok(out).build();
    }

    @GET
    @Path("/recent")
    public Response recent() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", audit.recentEvents());
        return Response.ok(out).build();
    }
}
