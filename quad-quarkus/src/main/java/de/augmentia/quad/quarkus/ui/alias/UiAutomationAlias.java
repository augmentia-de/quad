package de.augmentia.quad.quarkus.ui.alias;

import de.augmentia.quad.quarkus.automation.AutomationResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * UI-Alias (Base-Path {@code /api/ui}) for {@link AutomationResource}.
 */
@ApplicationScoped
@Path("/api/ui/automation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UiAutomationAlias {

    @Inject
    AutomationResource inner;

    @GET public Response list() { return inner.list(); }

    @GET @Path("/{taskId}")
    public Response get(@PathParam("taskId") String taskId) { return inner.get(taskId); }

    @POST public Response create(Map<String, Object> payload) { return inner.create(payload); }

    @PUT @Path("/{taskId}")
    public Response update(@PathParam("taskId") String taskId, Map<String, Object> changes) { return inner.update(taskId, changes); }

    @DELETE @Path("/{taskId}")
    public Response delete(@PathParam("taskId") String taskId) { return inner.delete(taskId); }

    @POST @Path("/{taskId}/mark-seen")
    public Response markSeen(@PathParam("taskId") String taskId) { return inner.markSeen(taskId); }

    @POST @Path("/{taskId}/prepare-run")
    public Response prepareManualRun(@PathParam("taskId") String taskId) { return inner.prepareManualRun(taskId); }

    @POST @Path("/{taskId}/finalize-run/{runId}")
    public Response finalizeManualRun(@PathParam("taskId") String taskId, @PathParam("runId") String runId) { return inner.finalizeManualRun(taskId, runId); }
}