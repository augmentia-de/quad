package de.augmentia.quad.quarkus.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
 * REST for the workspace registry (Port 02): open/recent/trust/roots/bindings.
 */
@ApplicationScoped
@Path("/api/workspace")
@Produces(MediaType.APPLICATION_JSON)
public class WorkspaceResource {

    @Inject
    WorkspaceService workspace;

    @GET
    @Path("/recent")
    public Response recent(@QueryParam("limit") int limit) {
        return Response.ok(workspace.recent(limit <= 0 ? 20 : limit)).build();
    }

    @POST
    @Path("/open")
    public Response open(Map<String, Object> body) {
        String path = body.get("path") != null ? String.valueOf(body.get("path")) : null;
        boolean create = Boolean.TRUE.equals(body.get("create"));
        return Response.ok(workspace.open(path, create)).build();
    }

    @GET
    @Path("/command-trust/{path}")
    public Response commandTrust(@PathParam("path") String path) {
        return Response.ok(workspace.commandTrust(path)).build();
    }

    @POST
    @Path("/trust")
    public Response setTrust(Map<String, Object> body) {
        String path = body.get("path") != null ? String.valueOf(body.get("path")) : null;
        boolean trusted = Boolean.TRUE.equals(body.get("trusted"));
        return Response.ok(workspace.setTrust(path, trusted, null)).build();
    }

    @GET
    @Path("/trusted")
    public Response trusted() {
        return Response.ok(workspace.trustedWorkspaces()).build();
    }

    @POST
    @Path("/temp/{sessionId}")
    public Response createTemp(@PathParam("sessionId") String sessionId, Map<String, Object> body) {
        boolean git = Boolean.TRUE.equals(body.get("git"));
        return Response.ok(workspace.createTemp(sessionId, git)).build();
    }

    @POST
    @Path("/save-as/{sessionId}")
    public Response saveAs(@PathParam("sessionId") String sessionId, Map<String, Object> body) {
        String target = body.get("path") != null ? String.valueOf(body.get("path")) : null;
        return Response.ok(workspace.saveAsProject(sessionId, target)).build();
    }

    @GET
    @Path("/roots/{sessionId}")
    public Response roots(@PathParam("sessionId") String sessionId) {
        return Response.ok(workspace.roots(sessionId)).build();
    }

    @POST
    @Path("/roots/{sessionId}")
    public Response addRoot(@PathParam("sessionId") String sessionId, Map<String, Object> body) {
        String path = body.get("path") != null ? String.valueOf(body.get("path")) : null;
        boolean writable = Boolean.TRUE.equals(body.get("writable"));
        return Response.ok(workspace.addRoot(sessionId, path, writable)).build();
    }

    @DELETE
    @Path("/roots/{sessionId}/{path}")
    public Response removeRoot(@PathParam("sessionId") String sessionId, @PathParam("path") String path) {
        return Response.ok(workspace.removeRoot(sessionId, path)).build();
    }

    @GET
    @Path("/project/{sessionId}/{kind}")
    public Response projectMenu(@PathParam("sessionId") String sessionId, @PathParam("kind") String kind) {
        return Response.ok(workspace.projectMenu(sessionId, kind)).build();
    }

    @POST
    @Path("/project/{sessionId}/{kind}")
    public Response setBinding(@PathParam("sessionId") String sessionId, @PathParam("kind") String kind, Map<String, Object> body) {
        String name = body.get("name") != null ? String.valueOf(body.get("name")) : null;
        return Response.ok(workspace.projectName(sessionId, kind, name)).build();
    }
}
