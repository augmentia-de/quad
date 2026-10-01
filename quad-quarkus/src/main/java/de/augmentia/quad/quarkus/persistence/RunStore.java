package de.augmentia.quad.quarkus.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import de.augmentia.quad.core.workflow.run.WorkflowNodeResult;
import de.augmentia.quad.core.workflow.run.WorkflowRunSnapshot;
import de.augmentia.quad.core.workflow.run.WorkflowRunState;
import de.augmentia.quad.quarkus.ui.PersistenceStore;
import de.augmentia.quad.workflow.engine.port.WorkflowRunPort;

@ApplicationScoped
public class RunStore implements WorkflowRunPort {

    private static final Logger log = Logger.getLogger(RunStore.class);

    @Inject
    PersistenceStore db;

    /** Persistierte Run-Zeile from der DB (workflow_state-Tabelle). */
    public record PersistedRun(String runId, String workflowId, String status,
                               String initialData, List<WorkflowNodeResult> nodeResults,
                               String executingFrom, int restartCount,
                               String startedAt, String finishedAt, long durationMs,
                               String kind, int stepIndex) {};

    private final ConcurrentHashMap<String, WorkflowRunState> runs = new ConcurrentHashMap<>();
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

    static WorkflowRunState fromPersisted(PersistedRun p) {
        WorkflowRunState s = new WorkflowRunState(p.runId(), p.workflowId(), p.startedAt());
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
    /**
     * Port-Methode: identisch zu {@link #getOrLoad(String)}.
     *
     * <p>Der Name folgt der Engine-Sicht ("lade den Lauf"), der App-Name
     * ("get-or-load") der Cache-Semantik. Beide bleiben bestehen, weil
     * {@code getOrLoad} auch ausserhalb des Ports benutzt wird.
     */
    @Override
    public WorkflowRunState load(String runId) {
        return getOrLoad(runId);
    }

    public WorkflowRunState getOrLoad(String runId) {
        WorkflowRunState cached = runs.get(runId);
        if (cached != null) return cached;
        PersistedRun p = db.loadRunFromDb(runId);
        if (p == null) return null;
        WorkflowRunState s = fromPersisted(p);
        runs.put(runId, s);
        runsByWorkflow.computeIfAbsent(s.workflowId, k -> new CopyOnWriteArrayList<>()).add(runId);
        return s;
    }

    public WorkflowRunState create(String workflowId, List<String> nodes) {
        return create(workflowId, nodes, "");
    }

    public WorkflowRunState create(String workflowId, List<String> nodes, String initialData) {
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        var state = new WorkflowRunState(runId, workflowId, Instant.now().toString());
        state.initialData = initialData != null ? initialData : "";
        for (var node : nodes) {
            state.nodeResults.add(new WorkflowNodeResult(node, "", "", "pending", "", ""));
        }
        runs.put(runId, state);
        runsByWorkflow.computeIfAbsent(workflowId, k -> new CopyOnWriteArrayList<>()).add(runId);
        persistRun(state);
        return state;
    }

    public WorkflowRunState get(String runId) { return runs.get(runId); }

    public List<WorkflowRunState> listForWorkflow(String workflowId) {
        var ids = runsByWorkflow.getOrDefault(workflowId, List.of());
        var result = new ArrayList<WorkflowRunState>();
        for (var id : ids) {
            var r = runs.get(id);
            if (r != null) result.add(r);
        }
        result.sort(Comparator.comparing((WorkflowRunState r) -> r.startedAt).reversed());
        return result;
    }

    public List<WorkflowRunState> listAll() {
        var all = new ArrayList<>(runs.values());
        all.sort(Comparator.comparing((WorkflowRunState r) -> r.startedAt).reversed());
        return all;
    }

    /**
     * Port-Methode: persistiert den Lauf in die {@code workflow_state}-Tabelle.
     *
     * <p>Der In-Memory-Cache braucht hier keine Zuweisung mehr: seit
     * {@link WorkflowRunState} derselbe Typ ist, den {@link #create} in
     * {@code runs} ablegt, mutiert die Engine bereits genau das gecachte
     * Objekt. Vorher waren Engine-Zustand und Store-Zustand zwei verschiedene
     * Objekte - dort haette ein reines DB-Schreiben den Cache veraltet
     * zurueckgelassen.
     */
    @Override
    public void save(WorkflowRunState run) {
        persistRun(run);
    }

    public void persistRun(WorkflowRunState run) {
        try {
            db.upsertRun(run);
        } catch (Exception e) {
            log.warnf(e, "Failed to persis run %s", run.runId);
        }
    }
}
