package de.augmentia.quad.quarkus.permission;

import de.augmentia.quad.core.guards.PermissionMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-session permission mode control (Port 02 Befehlsebene).
 */
@ApplicationScoped
@Path("/api/permission")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PermissionResource {

    @Inject
    PermissionService permissionService;

    @GET
    public Response all() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modes", permissionService.registry().allModes());
        return Response.ok(out).build();
    }

    @GET
    @Path("/{sessionId}")
    public Response getMode(@PathParam("sessionId") String sessionId) {
        return Response.ok(Map.of("sessionId", sessionId, "mode", permissionService.modeFor(sessionId))).build();
    }

    @PUT
    @Path("/{sessionId}/{mode}")
    public Response setMode(@PathParam("sessionId") String sessionId, @PathParam("mode") String mode) {
        try {
            PermissionMode parsed = PermissionMode.valueOf(mode.toUpperCase());
            permissionService.setMode(sessionId, parsed);
            return Response.ok(Map.of("sessionId", sessionId, "mode", parsed)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", "invalid mode: " + mode)).build();
        }
    }
}
