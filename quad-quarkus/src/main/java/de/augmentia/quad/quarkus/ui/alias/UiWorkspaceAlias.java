package de.augmentia.quad.quarkus.ui.alias;

import de.augmentia.quad.quarkus.workspace.WorkspaceResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * UI-Alias (Base-Path {@code /api/ui}) for {@link WorkspaceResource} — delegiert 1:1.
 * Dawith erreicht the quad-ui (VITE_API_BASE_URL=/api/ui) the Workspace-Routen.
 */
@ApplicationScoped
@Path("/api/ui/workspace")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UiWorkspaceAlias {

    @Inject
    WorkspaceResource inner;

    @GET @Path("/recent")
    public Response recent(@QueryParam("limit") int limit) { return inner.recent(limit); }

    @POST @Path("/open")
    public Response open(Map<String, Object> body) { return inner.open(body); }

    @GET @Path("/command-trust/{path}")
    public Response commandTrust(@PathParam("path") String path) { return inner.commandTrust(path); }

    @POST @Path("/trust")
    public Response setTrust(Map<String, Object> body) { return inner.setTrust(body); }

    @GET @Path("/trusted")
    public Response trusted() { return inner.trusted(); }

    @POST @Path("/temp/{sessionId}")
    public Response createTemp(@PathParam("sessionId") String sessionId, Map<String, Object> body) { return inner.createTemp(sessionId, body); }

    @POST @Path("/save-as/{sessionId}")
    public Response saveAs(@PathParam("sessionId") String sessionId, Map<String, Object> body) { return inner.saveAs(sessionId, body); }

    @GET @Path("/roots/{sessionId}")
    public Response roots(@PathParam("sessionId") String sessionId) { return inner.roots(sessionId); }

    @POST @Path("/roots/{sessionId}")
    public Response addRoot(@PathParam("sessionId") String sessionId, Map<String, Object> body) { return inner.addRoot(sessionId, body); }

    @DELETE @Path("/roots/{sessionId}/{path}")
    public Response removeRoot(@PathParam("sessionId") String sessionId, @PathParam("path") String path) { return inner.removeRoot(sessionId, path); }

    @GET @Path("/project/{sessionId}/{kind}")
    public Response projectMenu(@PathParam("sessionId") String sessionId, @PathParam("kind") String kind) { return inner.projectMenu(sessionId, kind); }

    @POST @Path("/project/{sessionId}/{kind}")
    public Response setBinding(@PathParam("sessionId") String sessionId, @PathParam("kind") String kind, Map<String, Object> body) { return inner.setBinding(sessionId, kind, body); }
}