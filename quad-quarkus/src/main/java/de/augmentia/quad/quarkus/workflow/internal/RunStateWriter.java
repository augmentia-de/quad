package de.augmentia.quad.quarkus.workflow.internal;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.quarkus.persistence.RunStore;
import de.augmentia.quad.quarkus.persistence.StepSnapshotStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Encapsulates persistence of individual node results in the run state and store.
 * Decouples executors from RunStore and reduces repeated code.
 */
@ApplicationScoped
public class RunStateWriter {

    @Inject RunStore runStore;
    @Inject StepSnapshotStore snapshotStore;

    public RunStateWriter() {
    }

    public void updateNode(RunStore.RunState run, String nodeId, String status, String input, String output) {
        if (run == null || run.nodeResults == null) return;
        for (int i = 0; i < run.nodeResults.size(); i++) {
            var r = run.nodeResults.get(i);
            if (r.nodeId().equals(nodeId)) {
                run.nodeResults.set(i, new RunStore.NodeRunResult(nodeId, r.type(), r.title(), status, output, input));
                return;
            }
        }
    }

    public void persist(RunStore.RunState run) {
        if (run != null) runStore.persistRun(run);
    }

    /** Updates the node result and persists the run. */
    public void updateAndPersist(RunStore.RunState run, String nodeId, String status, String input, String output) {
        updateNode(run, nodeId, status, input, output);
        persist(run);
    }

    /** Adds a description/finding to the session. */
    public void addFinding(AgentSessionState sessionState, String title, String output) {
        if (sessionState == null) return;
        sessionState.addFinding(firstLine(title, output));
    }

    /** Speichert an Step-Snapshot (Run-Kontext + Session-Summary). */
    public void saveCheckpoint(RunStore.RunState run, WorkflowExecutionContext ctx) {
        String snapshotJson = ctx.snapshotJson();
        String memorySummary = null;
        if (ctx.sessionState() != null && ctx.sessionState().memory() != null
                && ctx.sessionState().memory().hasSummary()) {
            memorySummary = ctx.sessionState().memory().summary();
        }
        snapshotStore.save(run.runId, run.stepIndex, run.status, snapshotJson, memorySummary);
    }

    private String firstLine(String title, String output) {
        String s = output != null ? output : "";
        int nl = s.indexOf('\n');
        String line = nl > 0 ? s.substring(0, nl) : (s.length() > 120 ? s.substring(0, 120) + "..." : s);
        return "[" + title + "] " + line;
    }
}