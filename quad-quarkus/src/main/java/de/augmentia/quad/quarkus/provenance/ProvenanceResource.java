package de.augmentia.quad.quarkus.provenance;

import de.augmentia.quad.core.observability.provenance.ProvenanceEntry;
import de.augmentia.quad.core.observability.provenance.ProvenanceTracker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provenance tracking — read endpoint only (Port 05).
 */
@ApplicationScoped
@Path("/api/provenance")
@Produces(MediaType.APPLICATION_JSON)
public class ProvenanceResource {

    @Inject
    ProvenanceTracker tracker;

    @GET
    public Response list(@QueryParam("session") String sessionId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<ProvenanceEntry> entries = tracker.forSession(sessionId);
        out.put("entries", entries);
        out.put("count", tracker.count(sessionId));
        return Response.ok(out).build();
    }

    @POST
    @Path("/reset")
    public Response reset(@QueryParam("session") String sessionId) {
        tracker.reset(sessionId);
        return Response.ok(Map.of("ok", true, "sessionId", sessionId)).build();
    }
}
