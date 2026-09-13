package de.augmentia.quad.quarkus.ui.alias;

import de.augmentia.quad.quarkus.permission.PermissionResource;
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

/**
 * UI-Alias (Base-Path {@code /api/ui}) for {@link PermissionResource} —
 * deckt {@code fetchPermissionAll / fetchPermissionMode / setPermissionMode} ab.
 */
@ApplicationScoped
@Path("/api/ui/permission")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UiPermissionAlias {

    @Inject
    PermissionResource inner;

    @GET
    public Response all() { return inner.all(); }

    @GET @Path("/{sessionId}")
    public Response getMode(@PathParam("sessionId") String sessionId) { return inner.getMode(sessionId); }

    @PUT @Path("/{sessionId}/{mode}")
    public Response setMode(@PathParam("sessionId") String sessionId, @PathParam("mode") String mode) { return inner.setMode(sessionId, mode); }
}