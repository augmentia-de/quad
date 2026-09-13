package de.augmentia.quad.quarkus.ui;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.security.AuditEvent;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.memory.SessionMemory;
import de.augmentia.quad.quarkus.agent.config.AgentBuilderConfig;
import de.augmentia.quad.quarkus.agent.config.GrantedDirsConfig;
import de.augmentia.quad.quarkus.contract.ApiDtos;
import de.augmentia.quad.quarkus.hitl.HitlService;
import de.augmentia.quad.quarkus.mcp.McpManagerService;
import de.augmentia.quad.quarkus.persistence.AuditStore;
import de.augmentia.quad.quarkus.persistence.JournalService;
import de.augmentia.quad.quarkus.persistence.RunStore;
import de.augmentia.quad.quarkus.persistence.SessionStore;
import de.augmentia.quad.quarkus.security.DbAuditLogger;
import de.augmentia.quad.quarkus.workflow.engine.WorkflowEngine;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;


import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@ApplicationScoped
@Path("/api/ui")
@Produces(MediaType.APPLICATION_JSON)
@RegisterForReflection
public class AgentController {

    private static final Logger log = Logger.getLogger(AgentController.class);

    @Inject UiConfig config;
    @Inject SharedState state;
    @Inject RunStore runStore;
    @Inject AuditStore auditStore;
    @Inject HitlService hitlService;
    @Inject McpManagerService mcpManager;
    @Inject JournalService journalService;
    @Inject SessionStore sessionStore;
    @Inject DbAuditLogger dbAudit;
    @Inject de.augmentia.quad.quarkus.messaging.ChannelAgentFactory agentFactory;
    @Inject WorkflowEngine workflowEngine;
    @Inject GrantedDirsConfig grantedDirsConfig;
    @Inject AgentBuilderConfig agentBuilderConfig;
    @Inject WorkflowTransferService workflowTransferService;

    @ConfigProperty(name = "quad.memory.max-messages", defaultValue = "50")
    int memoryMaxMessages;

    @ConfigProperty(name = "quad.agent.timeout-default-ms", defaultValue = "600000")
    long defaultExecuteTimeoutMs;

    @PostConstruct
    void init() {
        // Ensure the single base agent (the "base agent like QuadAgent") always exists. Specific
        // agents are created without a template; there is no dedicated planner agent.
        if (!state.agents().containsKey("default-agent")) {
            state.putAgent(new ApiDtos.AgentDefinition(
                "default-agent", "Default UI Agent", "General-purpose agent using LLM", "general",
                config.getModel() != null ? config.getModel() : "gpt-4o-mini",
                0.7, 4096, 1.0,
                config.getEnabledTools().toArray(new String[0]),
                new String[0], new String[0], "ua"
            ));
        }
//        try {
//            hitlService.checkpointService().createCheckpoint("ui-default", "executeBash", "rm -rf /");
//        } catch (Exception e) {
//            log.warnf("Demo-HITL-Checkpoint konnte not erstellt werden: %s", e.getMessage());
//        }
        log.infof("AgentController initialized. Model: %s, DummyMode: %s", config.getModel(), config.isDummyMode());
    }

    // ─── Agents CRUD ────────────────────────────────────────

    @GET @Path("/agents")
    public Response listAgents(@QueryParam("includeInactive") boolean includeInactive) {
        var all = state.agents().values();
        if (!includeInactive) {
            all = all.stream().filter(a -> a.active).toList();
        }
        return Response.ok(all.stream().map(ApiDtos.AgentDefinition::toJson).toList()).build();
    }

    @GET @Path("/agents/{id}")
    public Response getAgent(@PathParam("id") String id) {
        var agent = state.agents().get(id);
        if (agent == null) return Response.status(404).entity(Map.of("error", "Agent not found")).build();
        return Response.ok(agent.toJson()).build();
    }

    @POST @Path("/agents")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createAgent(ApiDtos.AgentCreateRequest request) {
        if (request.name == null || request.name.isBlank()) {
            return Response.status(400).entity(Map.of("error", "name required")).build();
        }
        var agent = new ApiDtos.AgentDefinition(
            "agent-" + UUID.randomUUID().toString().substring(0, 8),
            request.name, request.description,
            request.category != null ? request.category : "general",
            request.model != null ? request.model : config.getModel(),
            request.temperature > 0 ? request.temperature : 0.7,
            request.maxTokens > 0 ? request.maxTokens : 4096,
            request.topP > 0 ? request.topP : 1.0,
            request.tools != null ? request.tools : new String[0],
            request.guardrailsInput != null ? request.guardrailsInput : new String[0],
            request.guardrailsOutput != null ? request.guardrailsOutput : new String[0],
            request.agentType != null ? request.agentType : "ua",
            request.systemPrompt, request.userMessageTemplate, request.jsonOutput,
            request.chatParameters,
            request.active != null ? request.active : true,
            request.hooks != null ? request.hooks : new String[0],
            request.timeoutSeconds
        );
        agent.jsonOutputSchema = request.jsonOutputSchema;
        state.putAgent(agent);
        state.incMetrics();
        auditStore.add("audit-" + agent.id, Instant.now().toString(), "Admin", "Create", "Agent created: " + agent.id);
        return Response.status(201).entity(Map.of("id", agent.id)).build();
    }

    @PUT @Path("/agents/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateAgent(@PathParam("id") String id, ApiDtos.AgentCreateRequest request) {
        var existing = state.agents().get(id);
        if (existing == null) return Response.status(404).entity(Map.of("error", "Agent not found")).build();
        var updated = new ApiDtos.AgentDefinition(id, request.name, request.description,
            request.category, request.model, request.temperature, request.maxTokens, request.topP,
            request.tools, request.guardrailsInput, request.guardrailsOutput,
            request.agentType != null ? request.agentType : "ua",
            request.systemPrompt, request.userMessageTemplate, request.jsonOutput,
            request.chatParameters,
            request.active != null ? request.active : existing.active,
            request.hooks != null ? request.hooks : existing.hooks != null ? existing.hooks : new String[0],
            request.timeoutSeconds != null ? request.timeoutSeconds : existing.timeoutSeconds);
        updated.jsonOutputSchema = request.jsonOutputSchema;
        state.putAgent(updated);
        state.incMetrics();
        auditStore.add("audit-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString(),
            "Admin", "Update", "Agent updated: " + id);
        return Response.ok(updated.toJson()).build();
    }

    @POST @Path("/agents/{id}/duplicate")
    public Response duplicateAgent(@PathParam("id") String id) {
        var existing = state.agents().get(id);
        if (existing == null) return Response.status(404).entity(Map.of("error", "Agent not found")).build();
        var copy = new ApiDtos.AgentDefinition(
            "agent-" + UUID.randomUUID().toString().substring(0, 8),
            existing.name + " (copy)",
            existing.description, existing.category, existing.model,
            existing.temperature, existing.maxTokens, existing.topP,
            existing.tools != null ? existing.tools : new String[0],
            existing.guardrailsInput != null ? existing.guardrailsInput : new String[0],
            existing.guardrailsOutput != null ? existing.guardrailsOutput : new String[0],
            existing.agentType != null ? existing.agentType : "ua",
            existing.systemPrompt, existing.userMessageTemplate, existing.jsonOutput,
            existing.chatParameters,
            existing.active,
            existing.hooks != null ? existing.hooks : new String[0],
            existing.timeoutSeconds
        );
        copy.jsonOutputSchema = existing.jsonOutputSchema;
        state.putAgent(copy);
        state.incMetrics();
        auditStore.add("audit-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString(),
            "Admin", "Duplicate", "Agent duplicated: " + id + " -> " + copy.id);
        return Response.status(201).entity(Map.of("id", copy.id)).build();
    }

    @DELETE @Path("/agents/{id}")
    public Response deleteAgent(@PathParam("id") String id) {
        if (state.removeAgent(id) == null) {
            return Response.status(404).entity(Map.of("error", "Agent not found")).build();
        }
        state.incMetrics();
        auditStore.add("audit-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString(),
            "Admin", "Delete", "Agent deleted: " + id);
        return Response.noContent().build();
    }

    // ─── Execution ──────────────────────────────────────────

    @POST @Path("/execute")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response executeTask(ApiDtos.UiTaskRequest request) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        try {
            validateRequest(request);
        } catch (ApiDtos.ValidationException e) {
            return Response.status(400).entity(ApiDtos.UiExecutionResult.error("Validation: " + e.getMessage())).build();
        }
        log.infof("Executing task: %s", taskId);
        long start = System.currentTimeMillis();
        long timeoutMs = executionTimeoutMs(request.getAgentId());
        try {
            Agent agent = createConfiguredAgent(request.getAgentId());
            AgentSessionState sessionState = null;
            if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
                sessionState = sessionStore.loadState(request.getSessionId());
            }
            if (sessionState == null) {
                sessionState = newRunState();
            }
            final AgentSessionState fs = sessionState;
            String result;
            try {
                CompletableFuture<String> future =
                    CompletableFuture.supplyAsync(() -> agent.execute(request.getTask(), fs));
                result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                log.warnf("Task %s timed out after %dms", taskId, timeoutMs);
                sessionStore.recordEvent(sessionState.getSessionId(), "TASK_TIMEOUT",
                    "{\"timeoutMs\":" + timeoutMs + "}");
                return Response.status(504).entity(ApiDtos.UiExecutionResult.error(
                    "Timeout: agent execution exceeded " + timeoutMs + "ms")).build();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Task interrupted", ie);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                throw cause instanceof RuntimeException r ? r : new RuntimeException(cause);
            }
            long duration = System.currentTimeMillis() - start;
            int toolCount = agent.getToolRegistry().getSpecifications().size();
            var response = new ApiDtos.UiExecutionResult(
                sessionState.getSessionId(), true, result, toolCount, duration, config.getCostLimit(),
                request.getAgentId() != null ? "agent:" + request.getAgentId() : config.getModel());
            state.incMetrics();
            auditStore.add("audit-" + taskId, Instant.now().toString(),
                request.getAgentId() != null ? request.getAgentId() : "System", "Tool Execution", "Executed: " + request.getTask());
            sessionStore.upsertSession(sessionState.getSessionId(), request.getTask(), result,
                sessionState.memory().hasSummary() ? sessionState.memory().summary() : null, sessionState);
            sessionStore.recordEvent(sessionState.getSessionId(), "TASK_EXECUTED",
                "{\"toolCount\":" + toolCount + ",\"durationMs\":" + duration + "}");
            return Response.ok(response).build();
        } catch (Exception e) {
            log.errorf(e, "Task execution failed: %s", e.getMessage());
            return Response.status(500).entity(ApiDtos.UiExecutionResult.error("Error: " + e.getMessage())).build();
        }
    }

    // ─── Workflows ──────────────────────────────────────────

    @GET @Path("/workflows")
    public Response listWorkflows() {
        return Response.ok(state.workflows().values().stream().map(w -> Map.<String, Object>of(
            "id", w.id, "name", w.name, "nodeCount", w.nodes.size(),
            "edgeCount", w.edges.size(), "initialTask", w.initialTask != null ? w.initialTask : "",
            "createdAt", w.createdAt, "updatedAt", w.updatedAt
        )).toList()).build();
    }

    @GET @Path("/workflows/{id}")
    public Response getWorkflow(@PathParam("id") String id) {
        var workflow = state.workflows().get(id);
        if (workflow == null) return Response.status(404).entity(Map.of("error", "Workflow not found")).build();
        return Response.ok(Map.of("id", workflow.id, "name", workflow.name,
            "nodes", workflow.nodes, "edges", workflow.edges,
            "initialTask", workflow.initialTask != null ? workflow.initialTask : "",
            "createdAt", workflow.createdAt, "updatedAt", workflow.updatedAt)).build();
    }

    @POST @Path("/workflows")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createWorkflow(ApiDtos.WorkflowCreateRequest request) {
        String id = request.id != null && !request.id.isBlank() ? request.id
            : "workflow-" + UUID.randomUUID().toString().substring(0, 8);
        var now = new Date().toInstant().toString();
        var workflow = new ApiDtos.WorkflowDef(id,
            request.name != null && !request.name.isBlank() ? request.name : "Untitled Workflow",
            request.nodes != null ? request.nodes : List.of(),
            request.edges != null ? request.edges : List.of(),
            request.initialTask,
            now, now);
        state.putWorkflow(workflow);
        state.incMetrics();
        auditStore.add("audit-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString(),
            "System", "Create", "Workflow created: " + workflow.name);
        return Response.status(201).entity(Map.of("id", id)).build();
    }

    @PUT @Path("/workflows/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateWorkflow(@PathParam("id") String id, ApiDtos.WorkflowCreateRequest request) {
        var existing = state.workflows().get(id);
        if (existing == null) return Response.status(404).entity(Map.of("error", "Workflow not found")).build();
        var updated = new ApiDtos.WorkflowDef(id,
            request.name != null && !request.name.isBlank() ? request.name : existing.name,
            request.nodes != null ? request.nodes : List.of(),
            request.edges != null ? request.edges : List.of(),
            request.initialTask != null ? request.initialTask : existing.initialTask,
            existing.createdAt, new Date().toInstant().toString());
        state.putWorkflow(updated);
        state.incMetrics();
        auditStore.add("audit-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString(),
            "System", "Update", "Workflow updated: " + updated.name);
        return Response.ok(Map.of("id", updated.id, "name", updated.name,
            "nodes", updated.nodes, "edges", updated.edges,
            "initialTask", updated.initialTask != null ? updated.initialTask : "",
            "createdAt", updated.createdAt, "updatedAt", updated.updatedAt)).build();
    }

    @POST @Path("/workflows/import")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response importWorkflow(Map<String, Object> body,
            @QueryParam("strategy") @DefaultValue("clone") String strategy) {
        try {
            Map<String, Object> result = workflowTransferService.importWorkflow(body, strategy);
            auditStore.add("audit-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString(),
                "System", "Import", "Workflow imported (" + strategy + "): " + result.get("importedWorkflowIds"));
            return Response.status(201).entity(result).build();
        } catch (WorkflowTransferService.TransferException e) {
            return Response.status(e.status).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            log.errorf("Workflow import failed: %s", e.getMessage());
            return Response.status(400).entity(Map.of("error", String.valueOf(e.getMessage()))).build();
        }
    }

    @GET @Path("/workflows/export/{id}")
    public Response exportWorkflow(@PathParam("id") String id) {
        try {
            return Response.ok(workflowTransferService.export(id)).build();
        } catch (WorkflowTransferService.TransferException e) {
            return Response.status(e.status).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST @Path("/workflows/{id}/duplicate")
    public Response duplicateWorkflow(@PathParam("id") String id) {
        var existing = state.workflows().get(id);
        if (existing == null) return Response.status(404).entity(Map.of("error", "Workflow not found")).build();
        String newId = "workflow-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, String> nodeIdMap = new HashMap<>();
        var newNodes = new ArrayList<Map<String, Object>>();
        for (var node : existing.nodes) {
            var copy = new LinkedHashMap<>(node);
            String oldId = String.valueOf(node.get("id"));
            String newIdNode = "node-" + UUID.randomUUID().toString().substring(0, 8);
            nodeIdMap.put(oldId, newIdNode);
            copy.put("id", newIdNode);
            copy.put("createdAt", new Date().toInstant().toString());
            newNodes.add(copy);
        }
        var newEdges = new ArrayList<Map<String, Object>>();
        for (var edge : existing.edges) {
            var copy = new LinkedHashMap<>(edge);
            copy.remove("id");
            copy.put("id", "edge-" + UUID.randomUUID().toString().substring(0, 8));
            String source = String.valueOf(edge.get("source"));
            String target = String.valueOf(edge.get("target"));
            if (nodeIdMap.containsKey(source)) copy.put("source", nodeIdMap.get(source));
            if (nodeIdMap.containsKey(target)) copy.put("target", nodeIdMap.get(target));
            newEdges.add(copy);
        }
        var now = new Date().toInstant().toString();
        var workflow = new ApiDtos.WorkflowDef(newId,
            existing.name != null ? existing.name + " (copy)" : "Untitled Workflow (copy)",
            newNodes, newEdges, existing.initialTask, now, now);
        state.putWorkflow(workflow);
        state.incMetrics();
        auditStore.add("audit-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString(),
            "System", "Duplicate", "Workflow duplicated: " + id + " -> " + newId);
        return Response.status(201).entity(Map.of("id", newId)).build();
    }

    @DELETE @Path("/workflows/{id}")
    public Response deleteWorkflow(@PathParam("id") String id) {
        if (state.removeWorkflow(id) == null) {
            return Response.status(404).entity(Map.of("error", "Workflow not found")).build();
        }
        state.incMetrics();
        auditStore.add("audit-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString(),
            "System", "Delete", "Workflow deleted: " + id);
        return Response.noContent().build();
    }

    @POST @Path("/workflows/{id}/execute")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response executeWorkflow(@PathParam("id") String id, ApiDtos.WorkflowExecuteRequest request) {
        var workflow = state.workflows().get(id);
        if (workflow == null) return Response.status(404).entity(Map.of("error", "Workflow not found")).build();
        var nodeIds = workflow.nodes.stream().map(n -> String.valueOf(n.get("id"))).toList();
        String initialData = request != null ? request.initialDataString() : null;
        if (initialData == null || initialData.isBlank()) initialData = workflow.initialTask;
        var run = runStore.create(id, nodeIds, initialData);
        state.incMetrics();
        auditStore.add("audit-" + run.runId, Instant.now().toString(),
            id, "Workflow Run Started", run.runId + " (" + nodeIds.size() + " nodes, initialData="
                + (initialData != null && !initialData.isEmpty()) + ")");
        Thread t = new Thread(() -> workflowEngine.execute(workflow, run), "wf-run-" + run.runId);
        t.setDaemon(true);
        t.start();
        return Response.ok(Map.of("runId", run.runId, "workflowId", id,
            "initialData", initialData != null ? initialData : "")).build();
    }

    @GET @Path("/workflows/{id}/runs")
    public Response listWorkflowRuns(@PathParam("id") String id) {
        if (state.workflows().get(id) == null) return Response.status(404).entity(Map.of("error", "Workflow not found")).build();
        return Response.ok(runStore.listForWorkflow(id).stream()
            .map(RunStore.RunState::snapshot).map(RunStore.WorkflowRun::toJson).toList()).build();
    }

    // ─── Runs ───────────────────────────────────────────────

    @GET @Path("/workflow-runs")
    public Response listAllWorkflowRuns() {
        return Response.ok(runStore.listAll().stream()
                .map(RunStore.RunState::snapshot).map(RunStore.WorkflowRun::toJson).toList()).build();
    }

    @GET @Path("/runs/{runId}")
    public Response getRun(@PathParam("runId") String runId) {
        var run = runStore.getOrLoad(runId);
        if (run == null) return Response.status(404).entity(Map.of("error", "Run not found")).build();
        return Response.ok(run.snapshot().toJson()).build();
    }

    @POST @Path("/runs/{runId}/continue")
    public Response continueRun(@PathParam("runId") String runId) {
        var run = runStore.getOrLoad(runId);
        if (run == null) return Response.status(404).entity(Map.of("error", "Run not found")).build();
        var workflow = state.workflows().get(run.workflowId);
        if (workflow == null) return Response.status(404).entity(Map.of("error", "Workflow not found")).build();
        if ("running".equals(run.status)) {
            return Response.status(409).entity(Map.of("error", "Run is already running")).build();
        }
        run.status = "running";
        Thread t = new Thread(() -> workflowEngine.execute(workflow, run, true), "wf-continue-" + run.runId);
        t.setDaemon(true);
        t.start();
        return Response.ok(Map.of("runId", run.runId, "status", "running")).build();
    }

    @POST @Path("/runs/{runId}/restart")
    public Response restartRun(@PathParam("runId") String runId) {
        var run = runStore.getOrLoad(runId);
        if (run == null) return Response.status(404).entity(Map.of("error", "Run not found")).build();
        var workflow = state.workflows().get(run.workflowId);
        if (workflow == null) return Response.status(404).entity(Map.of("error", "Workflow not found")).build();
        run.restart();
        runStore.persistRun(run);
        Thread t = new Thread(() -> workflowEngine.execute(workflow, run, false), "wf-restart-" + run.runId);
        t.setDaemon(true);
        t.start();
        return Response.ok(Map.of("runId", run.runId, "status", "running", "restartCount", run.restartCount)).build();
    }

    // ─── GDPR ───────────────────────────────────────────────

    @GET @Path("/gdpr/export/{sessionId}")
    public Response gdprExport(@PathParam("sessionId") String sessionId) {
        var session = sessionStore.session(sessionId);
        if (session == null) return Response.status(404).entity(Map.of("error", "Session not found")).build();
        var events = sessionStore.events(sessionId);
        var sessionState = sessionStore.loadState(sessionId);
        Map<String, Object> state = null;
        if (sessionState != null) {
            state = new java.util.LinkedHashMap<>();
            state.put("sessionId", sessionState.getSessionId());
            state.put("tenantId", sessionState.getTenantId());
            state.put("findings", sessionState.findings());
            state.put("currentProject", sessionState.getCurrentProject());
            state.put("currentCwd", sessionState.currentCwd());
        }
        return Response.ok(Map.of("session", session, "events", events, "state", state)).build();
    }

    @DELETE @Path("/gdpr/delete/{sessionId}")
    public Response gdprDelete(@PathParam("sessionId") String sessionId) {
        var session = sessionStore.session(sessionId);
        if (session == null) return Response.status(404).entity(Map.of("error", "Session not found")).build();
        sessionStore.deleteSession(sessionId);
        return Response.noContent().build();
    }

    // ─── Sessions ────────────────────────────────────────────

    @GET @Path("/sessions")
    public Response listSessions() { return Response.ok(sessionStore.listSessions()).build(); }

    @GET @Path("/sessions/{id}")
    public Response getSession(@PathParam("id") String id) {
        var session = sessionStore.session(id);
        if (session == null) return Response.status(404).entity(Map.of("error", "Session not found")).build();
        return Response.ok(Map.of("session", session, "events", sessionStore.events(id))).build();
    }

    @GET @Path("/sessions/{id}/events")
    public Response getSessionEvents(@PathParam("id") String id) {
        return Response.ok(Map.of("sessionId", id, "events", sessionStore.events(id))).build();
    }

    @GET @Path("/sessions/{id}/dirs")
    public Response listGrantedDirs(@PathParam("id") String id) {
        var state = sessionStore.loadState(id);
        if (state == null) return Response.status(404).entity(Map.of("error", "Session state not found")).build();
        var dirs = state.grantedDirectories().stream()
                .map(gd -> Map.<String, Object>of("path", gd.path().toString(), "access", gd.access().name()))
                .toList();
        return Response.ok(Map.of("sessionId", id, "directories", dirs)).build();
    }

    @POST @Path("/sessions/{id}/dirs")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response grantDir(@PathParam("id") String id, GrantDirRequest req) {
        if (req == null || req.path == null || req.path.isBlank()) {
            return Response.status(400).entity(Map.of("error", "path is required")).build();
        }
        var state = sessionStore.loadState(id);
        if (state == null) return Response.status(404).entity(Map.of("error", "Session state not found")).build();
        boolean writable = Boolean.TRUE.equals(req.writable);
        var gd = state.grantDir(java.nio.file.Path.of(req.path), writable);
        sessionStore.upsertSession(id, null, null, null, state);
        auditStore.add("audit-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString(),
            "Admin", "Grant-Dir", "Granted " + gd.path() + " (writable=" + gd.isWritable() + ") to session " + id);
        return Response.ok(Map.of(
            "sessionId", id,
            "path", gd.path().toString(),
            "access", gd.access().name())).build();
    }

    @DELETE @Path("/sessions/{id}/dirs/{dir}")
    public Response revokeDir(@PathParam("id") String id, @PathParam("dir") String dir) {
        var state = sessionStore.loadState(id);
        if (state == null) return Response.status(404).entity(Map.of("error", "Session state not found")).build();
        boolean removed = state.revokeDir(java.nio.file.Path.of(dir));
        if (!removed) return Response.status(404).entity(Map.of("error", "Directory not granted")).build();
        sessionStore.upsertSession(id, null, null, null, state);
        auditStore.add("audit-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString(),
            "Admin", "Revoke-Dir", "Revoked " + dir + " from session " + id);
        return Response.noContent().build();
    }

    /** Request body DTO for granting a directory. */
    public static class GrantDirRequest {
        public String path;
        public Boolean writable;
    }

    // ─── Audit ──────────────────────────────────────────────

    @GET @Path("/audit/logs")
    public Response getAuditLogs(
        @QueryParam("period") String period,
        @QueryParam("agent") String agent,
        @QueryParam("eventType") String eventType
    ) {
        var logs = auditStore.getFiltered(period, agent, eventType);
        return Response.ok(logs.stream().map(AuditStore.AuditLogEntry::toJson).toList()).build();
    }

    @GET @Path("/audit")
    public Response getAuditEvents(
        @QueryParam("limit") int limit,
        @QueryParam("session") String sessionId,
        @QueryParam("eventType") String eventType
    ) {
        List<Map<String, Object>> rows = dbAudit.query(limit <= 0 ? 200 : limit, sessionId, eventType)
            .stream().map(this::toAuditRow).toList();
        return Response.ok(rows).build();
    }

    @GET @Path("/audit/recent")
    public Response getRecentAuditEvents() {
        List<Map<String, Object>> rows = dbAudit.recentEvents()
            .stream().map(this::toAuditRow).toList();
        return Response.ok(rows).build();
    }

    private Map<String, Object> toAuditRow(AuditEvent e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventType", e.eventType());
        row.put("userId", e.userId());
        row.put("roles", e.roles() != null ? e.roles() : List.of());
        row.put("sessionId", e.sessionId());
        row.put("toolName", e.toolName());
        row.put("toolArgs", e.toolArgs());
        row.put("result", e.result());
        row.put("isError", e.isError());
        row.put("durationMs", e.durationMs());
        row.put("correlationId", e.correlationId());
        row.put("tokenId", e.tokenId());
        row.put("ts", e.timestamp() != null ? e.timestamp().toString() : null);
        return row;
    }

    // ─── MCP ────────────────────────────────────────────────

    @GET @Path("/mcp/status")
    public Response mcpStatus() { return Response.ok(mcpManager.status()).build(); }

    @POST @Path("/mcp/reinit")
    public Response mcpReinit() { return Response.ok(mcpManager.reinit()).build(); }

    // ─── Journal ────────────────────────────────────────────

    @GET @Path("/journal")
    public Response journal(@QueryParam("limit") @DefaultValue("100") int limit) {
        var events = journalService.readEvents();
        if (limit > 0 && events.size() > limit) events = events.subList(events.size() - limit, events.size());
        return Response.ok(Map.of("enabled", journalService.isEnabled(), "count", events.size(), "events", events)).build();
    }

    @DELETE @Path("/journal")
    public Response clearJournal() { journalService.clear(); return Response.noContent().build(); }

    // ─── Agent creation helper ──────────────────────────────

    private AgentSessionState newRunState() {
        AgentSessionState s = new AgentSessionState();
        s.setMemory(new SessionMemory(memoryMaxMessages));
        if (grantedDirsConfig != null) {
            grantedDirsConfig.directories().forEach((path, writable) -> s.grantDir(path, writable));
        }
        // Make the base workspace (default: process CWD + all subdirs) writable.
        if (agentBuilderConfig != null) {
            s.grantDir(agentBuilderConfig.getWorkspace(), true);
        }
        return s;
    }

    Agent createConfiguredAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            agentId = "default-agent";
        }
        return agentFactory.create(agentId);
    }
    private void validateRequest(ApiDtos.UiTaskRequest request) {
        if (request == null || request.getTask() == null || request.getTask().trim().isEmpty())
            throw new ApiDtos.ValidationException("Task must not be null or empty");
        if (request.getTask().length() > 10000)
            throw new ApiDtos.ValidationException("Task too long (max 10000 chars)");
    }

    /** Runtime limit of a direct agent execution: per-agent override > global default. */
    private long executionTimeoutMs(String agentId) {
        if (agentId != null && !agentId.isBlank()) {
            ApiDtos.AgentDefinition def = state.agents().get(agentId);
            if (def != null && def.timeoutSeconds != null && def.timeoutSeconds > 0) {
                return def.timeoutSeconds * 1000L;
            }
        }
        return defaultExecuteTimeoutMs;
    }

    private boolean matchesPeriod(RunStore.RunState run, String period) {
        if (period == null || period.isBlank() || "all".equals(period)) return true;
        String startedAt = run.startedAt;
        LocalDateTime st;
        try {
            st = LocalDateTime.parse(startedAt);
        } catch (Exception e) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        return switch (period) {
            case "today" -> st.toLocalDate().equals(now.toLocalDate());
            case "week" -> st.isAfter(now.minusDays(7));
            case "month" -> st.isAfter(now.minusDays(30));
            default -> true;
        };
    }

    private boolean matchesKind(RunStore.RunState run, String kind) {
        if (kind == null || kind.isBlank() || "all".equals(kind)) return true;
        String k = String.valueOf(run.kind).toLowerCase(java.util.Locale.ROOT);
        return k.equals(kind.toLowerCase(java.util.Locale.ROOT));
    }

    private boolean matchesStatus(RunStore.RunState run, String status) {
        if (status == null || status.isBlank() || "all".equals(status)) return true;
        return status.equalsIgnoreCase(String.valueOf(run.status));
    }

    private boolean matchesRunId(RunStore.RunState run, String runId) {
        if (runId == null || runId.isBlank() || "all".equals(runId)) return true;
        return String.valueOf(run.runId).contains(runId)
                || String.valueOf(run.workflowId).contains(runId);
    }

}
