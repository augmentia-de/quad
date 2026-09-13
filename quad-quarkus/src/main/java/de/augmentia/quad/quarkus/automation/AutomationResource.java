package de.augmentia.quad.quarkus.automation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * REST endpoints for scheduled automations (Port 04).
 */
@ApplicationScoped
@Path("/api/automation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AutomationResource {

    @Inject
    AutomationService service;

    @GET public Response list() { return Response.ok(service.list()).build(); }

    @GET
    @Path("/{taskId}")
    public Response get(@PathParam("taskId") String taskId) {
        return Response.ok(service.get(taskId)).build();
    }

    @POST
    public Response create(Map<String, Object> payload) {
        return Response.status(201).entity(service.create(payload)).build();
    }

    @PUT
    @Path("/{taskId}")
    public Response update(@PathParam("taskId") String taskId, Map<String, Object> changes) {
        return Response.ok(service.update(taskId, changes)).build();
    }

    @DELETE
    @Path("/{taskId}")
    public Response delete(@PathParam("taskId") String taskId) {
        return Response.ok(service.delete(taskId)).build();
    }

    @POST
    @Path("/{taskId}/mark-seen")
    public Response markSeen(@PathParam("taskId") String taskId) {
        return Response.ok(service.markSeen(taskId)).build();
    }

    @POST
    @Path("/{taskId}/prepare-run")
    public Response prepareManualRun(@PathParam("taskId") String taskId) {
        return Response.ok(service.prepareManualRun(taskId)).build();
    }

    @POST
    @Path("/{taskId}/finalize-run/{runId}")
    public Response finalizeManualRun(@PathParam("taskId") String taskId, @PathParam("runId") String runId) {
        return Response.ok(service.finalizeManualRun(taskId, runId)).build();
    }
}
