package de.augmentia.quad.quarkus.ui;

import de.augmentia.quad.core.config.ModelTier;
import de.augmentia.quad.core.config.TieredModelConfig;
import de.augmentia.quad.quarkus.agent.hook.HookRegistry;
import de.augmentia.quad.quarkus.hitl.HitlService;
import de.augmentia.quad.quarkus.mcp.McpManagerService;
import de.augmentia.quad.quarkus.messaging.MessagingRouter;
import de.augmentia.quad.quarkus.messaging.TopicAgentMapping;
import de.augmentia.quad.quarkus.persistence.AuditStore;
import de.augmentia.quad.quarkus.persistence.TelemetryStore;
import de.augmentia.quad.quarkus.contract.ApiDtos;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;

@ApplicationScoped
@Path("/api/ui")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RegisterForReflection
public class SystemController {

    private static final Logger log = Logger.getLogger(SystemController.class);

    @Inject UiConfig config;
    @Inject SharedState state;
    @Inject AuditStore auditStore;
    @Inject HitlService hitlService;
    @Inject McpManagerService mcpManager;
    @Inject MessagingRouter messagingRouter;
    @Inject TopicAgentMapping topicAgentMapping;
    @Inject TelemetryStore telemetryStore;
    @Inject de.augmentia.quad.quarkus.skillstore.SkillResource skillResource;
    @Inject HookRegistry hookRegistry;


    @ConfigProperty(name = "quad.model.tiers.enabled", defaultValue = "true")
    boolean modelTiersEnabled;

    // ─── Status ─────────────────────────────────────────────

    @GET @Path("/status")
    public Response getStatus() {
        return Response.ok(Map.of(
            "ready", true, "model", config.getModel(), "costLimit", config.getCostLimit(),
            "baseUrl", config.getBaseUrl(), "dummyMode", config.isDummyMode())).build();
    }

    @GET @Path("/model/tiers")
    public Response getModelTiers() {
        if (!modelTiersEnabled) return Response.ok(Map.of("enabled", false)).build();
        try {
            var tieredConfig = TieredModelConfig.fromEnv();
            return Response.ok(Map.of("enabled", true, "defaultTier", tieredConfig.defaultTier().name(),
                "simple", summarize(tieredConfig, ModelTier.SIMPLE),
                "advanced", summarize(tieredConfig, ModelTier.ADVANCED))).build();
        } catch (Exception e) {
            return Response.ok(Map.of("enabled", true, "error", e.getMessage())).build();
        }
    }

    private Map<String, Object> summarize(TieredModelConfig cfg, ModelTier tier) {
        var c = cfg.forTier(tier);
        return Map.of("model", c.modelName() != null ? c.modelName() : "",
            "baseUrl", c.baseUrl() != null ? c.baseUrl() : "",
            "apiKeySet", c.apiKey() != null && !c.apiKey().isBlank());
    }

    // ─── Tools ──────────────────────────────────────────────

    @GET @Path("/tools")
    public Response listTools() {
        var tools = new ArrayList<Map<String, Object>>();
        for (String toolName : config.getEnabledTools()) {
            tools.add(Map.of("name", toolName, "description", getToolDescription(toolName), "source", "builtin"));
        }
        if (mcpManager.toolNames() != null && !mcpManager.toolNames().isEmpty()) {
            for (String mcpName : mcpManager.toolNames()) {
                tools.add(Map.of("name", mcpName, "description", "MCP Tool: dynamic", "source", "mcp"));
            }
        }
        return Response.ok(tools).build();
    }

    private String getToolDescription(String toolName) {
        return switch (toolName) {
            case "readFile" -> "Reads content from a file within the workspace";
            case "writeFile" -> "Writes content to a file within the workspace";
            case "webSearch" -> "Searches the web for information";
            case "webfetch" -> "Fetches content from a URL";
            case "findFiles" -> "Finds files in the workspace";
            case "grepSearch" -> "Searches for text pattern in files";
            case "appendFile" -> "Appends content to a file";
            default -> "Tool: " + toolName;
        };
    }

    // ─── Skills ─────────────────────────────────────────────

    @GET @Path("/skills")
    public Response listSkills() {
        return skillResource.list();
    }

    @GET @Path("/skills/{name}")
    public Response getSkill(@PathParam("name") String name) {
        return skillResource.get(name);
    }

    @PUT @Path("/skills/{name}")
    public Response upsertSkill(@PathParam("name") String name, Map<String, Object> payload) {
        return skillResource.upsert(name, payload);
    }

    @DELETE @Path("/skills/{name}")
    public Response deleteSkill(@PathParam("name") String name) {
        return skillResource.delete(name);
    }

    // ─── Guardrails ─────────────────────────────────────────

    @GET @Path("/guardrails")
    public Response listGuardrails() {
        var guardrailNames = new LinkedHashSet<String>();
        for (var def : state.agents().values()) {
            if (def.guardrailsInput != null) guardrailNames.addAll(List.of(def.guardrailsInput));
            if (def.guardrailsOutput != null) guardrailNames.addAll(List.of(def.guardrailsOutput));
        }
        return Response.ok(guardrailNames.stream().map(name -> Map.of(
            "name", name, "type", switch (name.toLowerCase()) {
                case "pii", "pii-filter", "email", "emails" -> "input";
                case "sql-injection", "sqli" -> "input";
                case "prompt-injection", "jailbreak" -> "output";
                default -> "unknown";
            }, "active", de.augmentia.quad.quarkus.guardrails.GuardrailFactory.fromName(name) != null
        )).toList()).build();
    }

    // ─── Hooks ──────────────────────────────────────────────

    @GET @Path("/hooks")
    public Response listHooks() {
        return Response.ok(hookRegistry.listAll()).build();
    }

    // ─── Metrics ────────────────────────────────────────────

    @GET @Path("/metrics")
    public Response getMetrics() {
        long total = 2000L + (state.metricsValue() * 150);
        return Response.ok(Map.of("prompt", total / 2, "completion", total / 3, "total", total)).build();
    }

    @GET @Path("/metrics/errors")
    public Response getErrorMetrics() {
        return Response.ok(Map.of("toolCalls", 5, "timeouts", 2, "guardrails", 1)).build();
    }

    // ─── Runs (vereinheitlichte Filter-Sicht, gleiche Daten wie UI) ──────

    /**
     * Returns runs as an aggregated view over {@link TelemetryStore#query}: per run
     * (run_id/session_id) builds a {@link ApiDtos.RunView} with steps, status and times.
     * Filters (period/kind/status/runId/sessionId) are applied to the raw events,
     * so the API and UI return identical records for the same filter.
     */
    @GET @Path("/runs")
    public Response getRuns(
        @QueryParam("period") String period,
        @QueryParam("kind") String kind,
        @QueryParam("status") String status,
        @QueryParam("runId") String runId,
        @QueryParam("sessionId") String sessionId
    ) {
        var rows = telemetryStore.query(period, null, null, runId, sessionId);
        var grouped = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var row : rows) {
            Object rid = row.get("runId");
            Object sid = row.get("sessionId");
            String id = rid != null ? String.valueOf(rid)
                : sid != null ? String.valueOf(sid)
                : "unknown";
            grouped.computeIfAbsent(id, k -> new ArrayList<>()).add(row);
        }

        var runs = new ArrayList<ApiDtos.RunView>();
        for (var entry : grouped.entrySet()) {
            runs.add(buildRunView(entry.getKey(), entry.getValue()));
        }
        runs.sort(Comparator.comparing((ApiDtos.RunView r) -> r.startedAt != null ? r.startedAt : "").reversed());

        var filtered = runs.stream()
            .filter(r -> matchesKind(r.kind, kind))
            .filter(r -> matchesStatus(r.status, status))
            .toList();
        return Response.ok(filtered).build();
    }

    private ApiDtos.RunView buildRunView(String runId, List<Map<String, Object>> rows) {
        var steps = new ArrayList<ApiDtos.StepView>();
        String earliest = null, latest = null;
        int modelCount = 0, toolCount = 0;
        boolean anyStarted = false, anyFailed = false, anyCompleted = false;
        boolean lifecycleCompleted = false, lifecycleFailed = false;

        for (var row : rows) {
            String eventType = String.valueOf(row.getOrDefault("eventType", ""));
            String ts = row.get("timestamp") != null ? String.valueOf(row.get("timestamp")) : null;
            if (earliest == null || (ts != null && ts.compareTo(earliest) < 0)) earliest = ts;
            if (latest == null || (ts != null && ts.compareTo(latest) > 0)) latest = ts;

            if ("RUN_COMPLETED".equals(eventType)) lifecycleCompleted = true;
            else if ("RUN_FAILED".equals(eventType)) lifecycleFailed = true;
            else if ("STEP".equals(eventType) || "SPAN".equals(eventType)) {
                var s = stepFromPayload(row, eventType);
                if (s != null) {
                    steps.add(s);
                    if ("model".equals(s.kind)) modelCount++;
                    if ("tool".equals(s.kind)) toolCount++;
                    switch (s.status) {
                        case "started", "running" -> anyStarted = true;
                        case "failed" -> anyFailed = true;
                        case "completed" -> anyCompleted = true;
                        default -> {}
                    }
                }
            }
        }

        String kind = toolCount > 0 ? "tool" : modelCount > 0 ? "model" : "run";
        String status;
        if (anyFailed || lifecycleFailed) status = "failed";
        else if (lifecycleCompleted) status = "completed";
        else if (anyStarted) status = "running";
        else status = anyCompleted ? "completed" : "running";

        long durMs = 0;
        if (earliest != null && latest != null) {
            try {
                durMs = java.time.Duration.between(java.time.Instant.parse(earliest),
                    java.time.Instant.parse(latest)).toMillis();
            } catch (Exception ignored) {}
        }

        return new ApiDtos.RunView(runId, kind, status, steps.size(),
            earliest, latest, durMs, steps);
    }

    private ApiDtos.StepView stepFromPayload(Map<String, Object> row, String eventType) {
        Object payloadObj = row.get("payload");
        if (payloadObj == null) return null;
        String payload = String.valueOf(payloadObj);
        String stepId = extract(payload, "stepId");
        if (stepId == null) stepId = extract(payload, "spanId");
        if (stepId == null) stepId = "unknown";
        String kind = extract(payload, "kind");
        if (kind == null) kind = "step";
        String status = extract(payload, "status");
        if (status == null) status = "unknown";
        long durMs = 0;
        try {
            durMs = Long.parseLong(extract(payload, "durationMs", "0"));
        } catch (Exception ignored) {}
        String ts = row.get("timestamp") != null ? String.valueOf(row.get("timestamp")) : null;
        return new ApiDtos.StepView(stepId, kind, status, durMs, eventType, ts);
    }

    private static String extract(String payload, String key) {
        return extract(payload, key, null);
    }

    private static String extract(String payload, String key, String def) {
        String marker = "\"" + key + "\":";
        int idx = payload.indexOf(marker);
        if (idx < 0) return def;
        int start = idx + marker.length();
        int end = payload.indexOf(',', start);
        if (end < 0) end = payload.indexOf('}', start);
        if (end < 0) end = payload.length();
        String val = payload.substring(start, end).trim();
        val = val.replaceAll("^\"|\"$", "");
        return val.isEmpty() ? def : val;
    }

    private static boolean matchesKind(String runKind, String kind) {
        if (kind == null || kind.isBlank() || "all".equals(kind)) return true;
        return kind.equalsIgnoreCase(String.valueOf(runKind));
    }

    private static boolean matchesStatus(String runStatus, String status) {
        if (status == null || status.isBlank() || "all".equals(status)) return true;
        return status.equalsIgnoreCase(String.valueOf(runStatus));
    }

    // ─── HITL ───────────────────────────────────────────────

    @GET @Path("/hitl/approvals")
    public Response getHitlApprovals() {
        var pending = hitlService.checkpointService().getAllPendingCheckpoints();
        return Response.ok(pending.stream().map(cp -> Map.<String, Object>of(
            "id", cp.id(), "agent", cp.sessionId(), "tool", cp.toolName(),
            "request", cp.arguments(), "since", cp.createdAt().toString(), "status", "pending"
        )).toList()).build();
    }

    @POST @Path("/hitl/approvals/{id}/approve")
    public Response approveHitl(@PathParam("id") String id) {
        boolean ok = hitlService.checkpointService().approve(id, "");
        if (!ok) return Response.status(404).entity(Map.of("error", "Approval not foand or not pending")).build();
        state.incMetrics();
        auditStore.add("audit-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString(),
            "System", "Guardrail", "HITL approved: " + id);
        return Response.ok(Map.of("approved", true, "id", id)).build();
    }

    @POST @Path("/hitl/approvals/{id}/reject")
    public Response rejectHitl(@PathParam("id") String id) {
        boolean ok = hitlService.checkpointService().reject(id, "");
        if (!ok) return Response.status(404).entity(Map.of("error", "Approval not foand or not pending")).build();
        state.incMetrics();
        auditStore.add("audit-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString(),
            "System", "Guardrail", "HITL rejected: " + id);
        return Response.ok(Map.of("rejected", true, "id", id)).build();
    }

    // ─── Messaging ──────────────────────────────────────────

    @GET @Path("/messaging/status")
    public Response messagingStatus() {
        Map<String, String> inbound = new LinkedHashMap<>();
        Map<String, String> outbound = new LinkedHashMap<>();
        try {
            var running = messagingRouter.getRunningChannels();
            if (running != null) {
                for (var entry : running.entrySet()) {
                    try {
                        inbound.put(entry.getKey(), entry.getValue().isRunning() ? "running" : "stopped");
                    } catch (Exception e) {
                        inbound.put(entry.getKey(), "unknown");
                    }
                }
            }
            var channels = messagingRouter.getOutboundChannels();
            if (channels != null) {
                for (var c : channels) {
                    try {
                        outbound.put(c.name(), c.isAvailable() ? "available" : "unavailable");
                    } catch (Exception e) {
                        outbound.put("unknown", "error");
                    }
                }
            }
        } catch (Exception e) {
            log.warnf("Error reading messaging status: %s", e.getMessage());
        }
        return Response.ok(Map.of(
            "enabled", !inbound.isEmpty() || !outbound.isEmpty(),
            "channels", List.of(),
            "inbound", inbound,
            "outbound", outbound
        )).build();
    }

    @GET @Path("/messaging/channels")
    public Response messagingChannels() {
        var mappings = topicAgentMapping.allMappings();
        var channels = new java.util.ArrayList<ApiDtos.MessagingChannelConfig>();
        for (var entry : mappings.entrySet()) {
            String[] parts = entry.getKey().split(":");
            if (parts.length == 3) {
                channels.add(new ApiDtos.MessagingChannelConfig(
                    parts[2], parts[0], parts[2], entry.getValue(), parts[1]));
            } else if (parts.length == 2) {
                channels.add(new ApiDtos.MessagingChannelConfig(
                    parts[1], parts[0], parts[1], entry.getValue(), "default"));
            }
        }
        return Response.ok(channels).build();
    }

}
