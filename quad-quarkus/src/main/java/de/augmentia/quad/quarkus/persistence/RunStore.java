package de.augmentia.quad.quarkus.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import de.augmentia.quad.quarkus.ui.PersistenceStore;

@ApplicationScoped
public class RunStore {

    private static final Logger log = Logger.getLogger(RunStore.class);

    @Inject
    PersistenceStore db;

    public record NodeRunResult(String nodeId, String type, String title, String status,
                                String output, String input) {}

    public record WorkflowRun(String runId, String workflowId, String status,
                              String startedAt, String finishedAt, long durationMs,
                              List<NodeRunResult> nodeResults, String initialData,
                              String executingFrom, int restartCount,
                              String kind, int stepIndex) {
        public Map<String, Object> toJson() {
            var m = new LinkedHashMap<String, Object>();
            m.put("runId", runId);
            m.put("workflowId", workflowId);
            m.put("status", status);
            m.put("startedAt", startedAt);
            m.put("finishedAt", finishedAt);
            m.put("durationMs", durationMs);
            m.put("initialData", initialData != null ? initialData : "");
            m.put("executingFrom", executingFrom != null ? executingFrom : "");
            m.put("restartCount", restartCount);
            m.put("kind", kind != null ? kind : "WORKFLOW");
            m.put("stepIndex", stepIndex);
            List<Map<String, Object>> results = new ArrayList<>();
            for (NodeRunResult n : nodeResults) {
                var r = new LinkedHashMap<String, Object>();
                r.put("nodeId", n.nodeId());
                r.put("type", n.type() != null ? n.type() : "");
                r.put("title", n.title() != null ? n.title() : "");
                r.put("status", n.status() != null ? n.status() : "");
                r.put("output", n.output() != null ? n.output() : "");
                r.put("input", n.input() != null ? n.input() : "");
                results.add(r);
            }
            m.put("nodeResults", results);
            return m;
        }
    }

    public static final class RunState {
        public final String runId;
        public final String workflowId;
        public final String startedAt;
        public volatile String status = "running";
        public volatile String finishedAt;
        public volatile long durationMs = 0;
        public volatile String initialData = "";
        public volatile String executingFrom;
        public volatile int restartCount = 0;
        /** Run kind: "WORKFLOW" (default) or "AGENT" - carries the shared run model. */
        public volatile String kind = "WORKFLOW";
        /** Step counter; basis for deterministic resume. */
        public volatile int stepIndex = 0;
        public final List<NodeRunResult> nodeResults = new CopyOnWriteArrayList<>();

        RunState(String runId, String workflowId) {
            this.runId = runId;
            this.workflowId = workflowId;
            this.startedAt = Instant.now().toString();
        }

        /** Resets the run and re-executes it from scratch (status + node results). */
        public void restart() {
            this.restartCount++;
            this.status = "running";
            this.finishedAt = null;
            this.durationMs = 0;
            this.executingFrom = null;
            this.stepIndex = 0;
            nodeResults.replaceAll(r -> new NodeRunResult(r.nodeId(), r.type(), r.title(), "pending", "", ""));
        }

        public WorkflowRun snapshot() {
            return new WorkflowRun(runId, workflowId, status, startedAt, finishedAt, durationMs,
                List.copyOf(nodeResults), initialData, executingFrom, restartCount, kind, stepIndex);
        }
    }

    /** Persistierte Run-Zeile from der DB (workflow_state-Tabelle). */
    public record PersistedRun(String runId, String workflowId, String status,
                               String initialData, List<NodeRunResult> nodeResults,
                               String executingFrom, int restartCount,
                               String startedAt, String finishedAt, long durationMs,
                               String kind, int stepIndex) {};

    private final ConcurrentHashMap<String, RunState> runs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> runsByWorkflow = new ConcurrentHashMap<>();

    /** Loads all runs from the workflow_state table on startup (current status always queryable). */
    @jakarta.annotation.PostConstruct
    void loadFromDb() {
        for (var p : db.listRunsFromDb()) {
            var state = fromPersisted(p);
            runs.put(state.runId, state);
            runsByWorkflow.computeIfAbsent(state.workflowId, k -> new CopyOnWriteArrayList<>()).add(state.runId);
        }
        if (!runs.isEmpty()) log.infof("RunStore: %d Runs from workflow_state geladen", runs.size());
    }

    static RunState fromPersisted(PersistedRun p) {
        RunState s = new RunState(p.runId(), p.workflowId());
        s.status = p.status();
        s.finishedAt = p.finishedAt();
        s.durationMs = p.durationMs();
        s.initialData = p.initialData() != null ? p.initialData() : "";
        s.executingFrom = p.executingFrom();
        s.restartCount = p.restartCount();
        s.kind = p.kind() != null ? p.kind() : "WORKFLOW";
        s.stepIndex = p.stepIndex();
        if (p.nodeResults() != null) s.nodeResults.addAll(p.nodeResults());
        return s;
    }

    /** Holt an persistierten Run from der DB (auch if er not im In-Memory-Cache liegt). */
    public RunState getOrLoad(String runId) {
        RunState cached = runs.get(runId);
        if (cached != null) return cached;
        PersistedRun p = db.loadRunFromDb(runId);
        if (p == null) return null;
        RunState s = fromPersisted(p);
        runs.put(runId, s);
        runsByWorkflow.computeIfAbsent(s.workflowId, k -> new CopyOnWriteArrayList<>()).add(runId);
        return s;
    }

    public RunState create(String workflowId, List<String> nodes) {
        return create(workflowId, nodes, "");
    }

    public RunState create(String workflowId, List<String> nodes, String initialData) {
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        var state = new RunState(runId, workflowId);
        state.initialData = initialData != null ? initialData : "";
        for (var node : nodes) {
            state.nodeResults.add(new NodeRunResult(node, "", "", "pending", "", ""));
        }
        runs.put(runId, state);
        runsByWorkflow.computeIfAbsent(workflowId, k -> new CopyOnWriteArrayList<>()).add(runId);
        persistRun(state);
        return state;
    }

    public RunState get(String runId) { return runs.get(runId); }

    public List<RunState> listForWorkflow(String workflowId) {
        var ids = runsByWorkflow.getOrDefault(workflowId, List.of());
        var result = new ArrayList<RunState>();
        for (var id : ids) {
            var r = runs.get(id);
            if (r != null) result.add(r);
        }
        result.sort(Comparator.comparing((RunState r) -> r.startedAt).reversed());
        return result;
    }

    public List<RunState> listAll() {
        var all = new ArrayList<>(runs.values());
        all.sort(Comparator.comparing((RunState r) -> r.startedAt).reversed());
        return all;
    }

    public void persistRun(RunState run) {
        try {
            db.upsertRun(run);
        } catch (Exception e) {
            log.warnf(e, "Failed to persis run %s", run.runId);
        }
    }
}
