package de.augmentia.quad.quarkus.workflow;

import de.augmentia.quad.quarkus.ui.SharedState;
import de.augmentia.quad.quarkus.persistence.AuditStore;
import de.augmentia.quad.quarkus.contract.ApiDtos;
import de.augmentia.quad.quarkus.persistence.RunStore;
import de.augmentia.quad.quarkus.workflow.engine.WorkflowEngine;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic workflow creation: given a task, an LLM decomposes it into a workflow that is
 * returned for preview, can be executed immediately, and can be saved (in any state) as a
 * new editable workflow that appears in the classic Workflow page.
 *
 * <p>Endpoints (all under {@code /api/ui/dynamic}):</p>
 * <ul>
 *   <li>{@code POST /generate} — task → {@link ApiDtos.WorkflowDef} (not persisted)</li>
 *   <li>{@code POST /run} — execute a generated workflow directly (not persisted)</li>
 *   <li>{@code POST /save} — persist a generated workflow as a new workflow (own ID)</li>
 * </ul>
 */
@Path("/api/ui/dynamic")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DynamicWorkflowResource {

    @Inject WorkflowGeneratorService generator;
    @Inject
    WorkflowEngine workflowEngine;
    @Inject SharedState state;
    @Inject RunStore runStore;
    @Inject AuditStore auditStore;

    /** request: { task, name? } */
    @RegisterForReflection
    public static class GenerateRequest {
        public String task;
        public String name;
    }

    /**
     * request: { workflowId? , workflow? , initialData? } — either a previously-saved workflow
     * id (looked up via {@link SharedState}) or the inline workflow definition to execute.
     */
    @RegisterForReflection
    public static class RunRequest {
        public String workflowId;
        public ApiDtos.WorkflowDef workflow;
        public String initialData;
    }

    /** request: { runId, nodeId, output? } for a deferred async completion callback. */
    @RegisterForReflection
    public static class CompleteRequest {
        public String runId;
        public String nodeId;
        public String output;
    }

    /** request: { task, name? } — start a new interactive chat session (Variante B). */
    @RegisterForReflection
    public static class ChatStartRequest {
        public String task;
        public String name;
    }

    /** request: { sessionId, request } — continue an interactive chat session. */
    @RegisterForReflection
    public static class ChatRequest {
        public String sessionId;
        public String request;
    }

    /**
     * Resolves a Deferred Completion for an {@code async} node. An external system (other
     * program, human approver, separate workflow) that was handed work by the async node calls
     * this once the result is ready; the run resumes so downstream nodes / the aggregator run.
     */
    @jakarta.ws.rs.POST
    @Path("/complete")
    public Response complete(CompleteRequest req) {
        if (req == null || req.runId == null || req.runId.isBlank()
            || req.nodeId == null || req.nodeId.isBlank()) {
            return Response.status(400).entity(Map.of("error", "runId and nodeId required")).build();
        }
        try {
            workflowEngine.completeDeferredNode(req.runId, req.nodeId,
                req.output != null ? req.output : "");
            return Response.ok(Map.of("ok", true, "runId", req.runId, "nodeId", req.nodeId)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @jakarta.ws.rs.POST
    @Path("/generate")
    public Response generate(GenerateRequest req) {
        if (req == null || req.task == null || req.task.isBlank()) {
            return Response.status(400).entity(Map.of("error", "task required")).build();
        }
        try {
            ApiDtos.WorkflowDef wf = generator.generate(req.task, req.name);
            return Response.ok(Map.of("workflow", wf)).build();
        } catch (Exception e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /** Start a new interactive chat session that returns a full workflow each round. */
    @jakarta.ws.rs.POST
    @Path("/chat/start")
    public Response chatStart(ChatStartRequest req) {
        if (req == null || req.task == null || req.task.isBlank()) {
            return Response.status(400).entity(Map.of("error", "task required")).build();
        }
        try {
            WorkflowGeneratorService.ChatDesc desc = generator.startChat(req.task, req.name);
            return Response.ok(Map.of("sessionId", desc.sessionId(),
                "workflow", desc.workflow(), "reply", desc.reply())).build();
        } catch (Exception e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /** Continue an interactive chat session, refining the workflow based on the request. */
    @jakarta.ws.rs.POST
    @Path("/chat")
    public Response chat(ChatRequest req) {
        if (req == null || req.sessionId == null || req.sessionId.isBlank()) {
            return Response.status(400).entity(Map.of("error", "sessionId required")).build();
        }
        if (req.request == null || req.request.isBlank()) {
            return Response.status(400).entity(Map.of("error", "request required")).build();
        }
        try {
            WorkflowGeneratorService.ChatDesc desc = generator.chat(req.sessionId, req.request);
            return Response.ok(Map.of("sessionId", desc.sessionId(),
                "workflow", desc.workflow(), "reply", desc.reply())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /** List active chat sessions (ids + current step counts) for diagnostics. */
    @GET
    @Path("/chat/sessions")
    public Response chatSessions() {
        return Response.ok(Map.of("sessions", generator.chatSessions())).build();
    }

    @jakarta.ws.rs.POST
    @Path("/run")
    public Response run(RunRequest req) {
        if (req == null || (req.workflowId == null || req.workflowId.isBlank())
            && req.workflow == null) {
            return Response.status(400).entity(Map.of("error", "workflowId or workflow required")).build();
        }
        if (req.workflow == null) {
            req.workflow = state.workflows().get(req.workflowId);
            if (req.workflow == null) {
                return Response.status(404).entity(Map.of(
                    "error", "workflow not found: " + req.workflowId)).build();
            }
        }
        if (req.workflow.nodes == null || req.workflow.nodes.isEmpty()) {
            return Response.status(400).entity(Map.of("error", "workflow has no nodes")).build();
        }
        var nodeIds = req.workflow.nodes.stream()
            .map(n -> String.valueOf(n.get("id"))).toList();
        String initialData = req.initialData != null && !req.initialData.isBlank()
            ? req.initialData : req.workflow.initialTask;
        var run = runStore.create(req.workflow.id, nodeIds, initialData);
        auditStore.add("audit-" + UUID.randomUUID().toString().substring(0, 8),
            Instant.now().toString(), req.workflow.id,
            "Dynamic Workflow Run Started", run.runId + " (" + nodeIds.size() + " nodes)");
        Thread t = new Thread(() -> workflowEngine.execute(req.workflow, run),
            "wf-dyn-" + run.runId);
        t.setDaemon(true);
        t.start();
        return Response.ok(Map.of("runId", run.runId, "workflowId", req.workflow.id,
            "initialData", initialData != null ? initialData : "")).build();
    }

    @jakarta.ws.rs.POST
    @Path("/save")
    public Response save(ApiDtos.WorkflowCreateRequest request) {
        if (request == null || request.nodes == null || request.nodes.isEmpty()) {
            return Response.status(400).entity(Map.of("error", "nodes required")).build();
        }
        // Always persist as a NEW workflow with its own id (any state is savable).
        String id = "workflow-" + UUID.randomUUID().toString().substring(0, 8);
        var now = Instant.now().toString();
        var workflow = new ApiDtos.WorkflowDef(id,
            request.name != null && !request.name.isBlank() ? request.name : "Untitled Workflow",
            request.nodes != null ? request.nodes : List.of(),
            request.edges != null ? request.edges : List.of(),
            request.initialTask, now, now);
        state.putWorkflow(workflow);
        state.incMetrics();
        auditStore.add("audit-" + UUID.randomUUID().toString().substring(0, 8),
            Instant.now().toString(), id, "Create", "Dynamic workflow saved: " + workflow.name);
        return Response.status(201).entity(Map.of("id", id)).build();
    }

    @GET
    @Path("/ping")
    public Response ping() {
        return Response.ok(Map.of("ok", true)).build();
    }
}
