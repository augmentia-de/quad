package de.augmentia.quad.workflow.engine;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.contract.ApiDtos;
import de.augmentia.quad.workflow.engine.port.RunEventPort;
import de.augmentia.quad.workflow.engine.port.StepCheckpointPort;
import de.augmentia.quad.workflow.engine.port.WorkflowDefinitionPort;
import de.augmentia.quad.workflow.engine.port.WorkflowRunPort;
import de.augmentia.quad.core.workflow.run.WorkflowRunState;
import de.augmentia.quad.workflow.engine.internal.*;
import de.augmentia.quad.workflow.engine.node.executor.AgentNodeExecutor;
import de.augmentia.quad.workflow.engine.node.NodeExecutor;
import de.augmentia.quad.workflow.engine.node.NodeExecutorRegistry;
import de.augmentia.quad.workflow.engine.node.NodeRunner;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lean facade / orchestrator of workflow execution. Decides between sequential
 * (topology) and parallel (ready-batch) execution, handles Restore/Continue and the
 * top-level persistence (run, audit, session). The actual node execution is split
 * by type in {@link NodeExecutor}; the graph control lives in {@link WorkflowScheduler}.
 */
public class WorkflowEngine {

    private static final Logger log = Logger.getLogger(WorkflowEngine.class);

    private final WorkflowDefinitionPort definitions;
    private final WorkflowSupport support;
    private final WorkflowScheduler scheduler;
    private final NodeExecutorRegistry registry;
    private final AgentNodeExecutor agentExecutor;
    private final RunStateWriter runStateWriter;
    private final WorkflowRunPort runPort;
    private final RunEventPort runEvents;
    private final StepCheckpointPort stepCheckpoints;

    public void execute(ApiDtos.WorkflowDef workflow, WorkflowRunState run) {
        execute(workflow, run, false);
    }

    /**
     * Executes a workflow. With {@code resume=true} (Continue / Crash-Recovery) the
     * already completed nodes are restored from {@code run.nodeResults} (equivalent to
     * step_results) and skipped; open/failed nodes are re-run.
     */
    public void execute(ApiDtos.WorkflowDef workflow, WorkflowRunState run, boolean resume) {
        long startMs = System.currentTimeMillis();
        AgentSessionState sessionState = support.newRunState();
        int initialDataLen = run.initialData != null ? run.initialData.length() : 0;
        runEvents.recordEvent(run.runId, resume ? "RUN_RESUMED" : "RUN_STARTED",
            "{\"workflow\":\"" + workflow.name + "\",\"initialDataLen\":" + initialDataLen + "}");
        runEvents.recordLifecycle(run.runId, resume ? "RUN_RESUMED" : "RUN_STARTED", Instant.now());
        log.infof("══ WORKFLOW ▶ [%s] RUN_%s workflow=\"%s\" (%d nodes, %d edges, initialData=%d chars) ══",
            run.runId, resume ? "RESUMED" : "STARTED", workflow.name,
            workflow.nodes != null ? workflow.nodes.size() : 0,
            workflow.edges != null ? workflow.edges.size() : 0, initialDataLen);

        if (resume) {
            run.status = "running";
            run.finishedAt = null;
        }
        Map<String, String> restoredOutputs = restoredOutputs(run);
        Set<String> completedNodes = completedNodeIds(run);
        String initialData = run.initialData != null ? run.initialData : "";

        WorkflowExecutionContext ctx = new WorkflowExecutionContext(run.runId, run, initialData, initialData,
            sessionState, resume, workflow.cachePrefix, Boolean.TRUE.equals(workflow.prefixCaching));
        if (restoredOutputs != null) ctx.outputs().putAll(restoredOutputs);
        if (resume) {
            // level 12: Losgeht's from the persistierten, versions-tragen Snapshot — reicher Loop/Join/
            // async state (outputs, statuses, joinDeadline, loopIterations), not just nodeResults.
            stepCheckpoints.loadLast(run.runId).ifPresent(snap -> {
                ctx.restoreSnapshot(snap.environmentState());
                log.infof("══ WORKFLOW ↻ [%s] RESUME — Snapshot step=%d gela (status=%s) ══",
                    run.runId, snap.stepIndex(), snap.stepStatus());
            });
        }
        support.registerRun(run.runId, ctx.outputs());

        try {
            if (requiresParallelism(workflow.nodes)) {
                scheduler.executeParallel(workflow.nodes, workflow.edges, ctx, this::runNode);
            } else {
                var ordered = GraphUtils.topologicalSort(workflow.nodes, workflow.edges);
                var skip = new HashSet<String>();
                if (completedNodes != null) skip.addAll(completedNodes);
                for (var node : ordered) {
                    runNode(node, workflow.nodes, workflow.edges, ctx, skip);
                }
            }
            support.removeRun(run.runId);

            run.durationMs = System.currentTimeMillis() - startMs;
            run.finishedAt = Instant.now().toString();
            run.status = "completed";
            runPort.save(run);
            runEvents.incrementRunCounter();
            runEvents.audit("audit-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString(),
                workflow.id, "Workflow Run Completed", run.runId + " in " + run.durationMs + "ms");
            runEvents.upsertRunSession(run.runId, "Workflow: " + workflow.name,
                "Run " + run.runId + " completed in " + run.durationMs + "ms",
                sessionState.memory().hasSummary() ? sessionState.memory().summary() : null, sessionState);
            runEvents.recordEvent(run.runId, "RUN_COMPLETED", "{\"durationMs\":" + run.durationMs + "}");
            runEvents.recordLifecycle(run.runId, "RUN_COMPLETED", Instant.now());
            log.infof("══ WORKFLOW ■ [%s] RUN_COMPLETED in %d ms (status=%s) ══",
                run.runId, run.durationMs, run.status);

        } catch (Exception e) {
            log.errorf("Workflow run %s failed: %s", run.runId, e.getMessage());
            run.status = "failed";
            run.finishedAt = Instant.now().toString();
            run.durationMs = System.currentTimeMillis() - startMs;
            runPort.save(run);
            runEvents.incrementRunCounter();
            runEvents.audit("audit-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString(),
                workflow.id, "Workflow Run Failed", run.runId + ": " + e.getMessage());
            runEvents.upsertRunSession(run.runId, "Workflow: " + workflow.name,
                "Run " + run.runId + " failed: " + e.getMessage(),
                sessionState.memory().hasSummary() ? sessionState.memory().summary() : null, null);
            runEvents.recordEvent(run.runId, "RUN_FAILED", "{\"error\":\"" + e.getMessage() + "\"}");
            runEvents.recordLifecycle(run.runId, "RUN_FAILED", Instant.now());
        }
    }

    private boolean requiresParallelism(List<Map<String, Object>> nodes) {
        for (var n : nodes) {
            String t = String.valueOf(n.get("type"));
            if ("fork".equals(t) || "async".equals(t) || "join".equals(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dispatches a single workflow node to the matching {@link NodeExecutor}.
     * Acts as {@link NodeRunner} for recursive calls (Loop/Conditional/Nested/Async).
     */
    void runNode(Map<String, Object> node, List<Map<String, Object>> allNodes, List<Map<String, Object>> edges,
                 WorkflowExecutionContext ctx, Set<String> skip) {
        String nodeId = String.valueOf(node.get("id"));
        if (skip != null && (skip.contains(nodeId) || ctx.hasOutput(nodeId))) return;
        if (ctx.hasOutput(nodeId)) return;

        String nodeType = node.get("type") != null ? String.valueOf(node.get("type")) : "";
        String nodeTitle = node.get("title") != null ? String.valueOf(node.get("title")) : "";
        log.infof("══ WORKFLOW ❯ [%s] %s %s (%s) ══",
            ctx.runId(), nodeType, nodeId, nodeTitle.isBlank() ? "«position»" : nodeTitle);

        NodeExecutor executor = registry.forType(nodeType);
        long startMs = System.currentTimeMillis();
        if (executor != null) {
            executor.execute(node, allNodes, edges, ctx, this::runNode);
            afterNode(node, ctx, startMs);
            return;
        }
        agentExecutor.execute(node, allNodes, edges, ctx, this::runNode);
        afterNode(node, ctx, startMs);
    }

    /** Increments stepIndex, saves the step snapshot and the telemetry. */
    private void afterNode(Map<String, Object> node, WorkflowExecutionContext ctx, long startMs) {
        WorkflowRunState run = ctx.run();
        // Sub-contexts (e.g. NestedWorkflowNodeExecutor) have no RunState — synchronising
        // on null would be an NPE ("Cannot enter synchronized block because 'run' is null").
        if (run != null) {
            synchronized (run) {
                run.stepIndex++;
            }
            runStateWriter.saveCheckpoint(run, ctx);
        }

        String nodeId = String.valueOf(node.get("id"));
        String nodeType = node.get("type") != null ? String.valueOf(node.get("type")) : "";
        String status = ctx.statuses().getOrDefault(nodeId, "completed");
        long durMs = System.currentTimeMillis() - startMs;
        if (run != null) {
            runEvents.recordStep(run.runId, nodeId, nodeType, status, durMs);
        }
    }

    public void completeDeferredNode(String runId, String nodeId, String output) {
        support.completeDeferred(runId, nodeId, output);
    }

    public boolean onMessage(String tenantId, String topic, String payload) {
        return support.enqueueMessage(tenantId + ":" + topic, payload);
    }

    public boolean hasPendingHandler(String tenantId, String topic) {
        return support.hasPendingHandlerFor(tenantId + ":" + topic);
    }

    // ── Restore-Helfer ─────────────────────────────────────────

    private Map<String, String> restoredOutputs(WorkflowRunState run) {
        Map<String, String> result = new java.util.HashMap<>();
        if (run == null || run.nodeResults == null) return result;
        for (var r : run.nodeResults) {
            if ("completed".equals(r.status()) && r.output() != null) {
                result.put(r.nodeId(), r.output());
            }
        }
        return result;
    }

    private Set<String> completedNodeIds(WorkflowRunState run) {
        Set<String> result = new HashSet<>();
        if (run == null || run.nodeResults == null) return result;
        for (var r : run.nodeResults) {
            if ("completed".equals(r.status())) result.add(r.nodeId());
        }
        return result;
    }

    public WorkflowEngine(final WorkflowDefinitionPort definitions, final WorkflowSupport support, final WorkflowScheduler scheduler, final NodeExecutorRegistry registry, final AgentNodeExecutor agentExecutor, final RunStateWriter runStateWriter,
                        final WorkflowRunPort runPort,
                        final RunEventPort runEvents,
                        final StepCheckpointPort stepCheckpoints) {
        this.definitions = definitions;
        this.support = support;
        this.scheduler = scheduler;
        this.registry = registry;
        this.agentExecutor = agentExecutor;
        this.runStateWriter = runStateWriter;
        this.runPort = runPort;
        this.runEvents = runEvents;
        this.stepCheckpoints = stepCheckpoints;
    }
}