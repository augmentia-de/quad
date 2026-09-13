package de.augmentia.quad.examples;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/workflows")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkflowResource {

    @Inject TwoStageWorkflowService workflowService;

    @POST
    @Path("/two-stage")
    public Response runWorkflow(WorkflowRequest request) {
        TwoStageWorkflowSession resultSession = workflowService.executeWorkflow(request.requirements());

        WorkflowResponse response = new WorkflowResponse(
            resultSession.getSessionId(),
            resultSession.getRequirementAnalysis(),
            resultSession.getArchitectureDesign(),
            resultSession.findings()
        );

        return Response.ok(response).build();
    }

    public record WorkflowRequest(String requirements) {}
    public record WorkflowResponse(
        String sessionId,
        String stage1Analysis,
        String stage2Architecture,
        java.util.List<String> auditFindings
    ) {}
}