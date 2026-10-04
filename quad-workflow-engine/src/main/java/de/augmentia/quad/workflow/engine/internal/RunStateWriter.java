package de.augmentia.quad.workflow.engine.internal;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.workflow.run.WorkflowNodeResult;
import de.augmentia.quad.core.workflow.run.WorkflowRunState;
import de.augmentia.quad.workflow.engine.port.StepCheckpointPort;
import de.augmentia.quad.workflow.engine.port.WorkflowRunPort;

/**
 * Encapsulates persistence of individual node results in the run state and store.
 * Decouples executors from the persistence port and reduces repeated code.
 */
public class RunStateWriter {

    private final WorkflowRunPort runPort;
    private final StepCheckpointPort checkpointPort;

    public RunStateWriter(final WorkflowRunPort runPort, final StepCheckpointPort checkpointPort) {
        this.runPort = runPort;
        this.checkpointPort = checkpointPort;
    }

    public void updateNode(WorkflowRunState run, String nodeId, String status, String input, String output) {
        if (run == null || run.nodeResults == null) return;
        for (int i = 0; i < run.nodeResults.size(); i++) {
            var r = run.nodeResults.get(i);
            if (r.nodeId().equals(nodeId)) {
                run.nodeResults.set(i, new WorkflowNodeResult(nodeId, r.type(), r.title(), status, output, input));
                return;
            }
        }
    }

    public void persist(WorkflowRunState run) {
        if (run != null) runPort.save(run);
    }

    /** Updates the node result and persists the run. */
    public void updateAndPersist(WorkflowRunState run, String nodeId, String status, String input, String output) {
        updateNode(run, nodeId, status, input, output);
        persist(run);
    }

    /** Adds a description/finding to the session. */
    public void addFinding(AgentSessionState sessionState, String title, String output) {
        if (sessionState == null) return;
        sessionState.addFinding(firstLine(title, output));
    }

    /** Stores a step snapshot (run context + session summary). */
    public void saveCheckpoint(WorkflowRunState run, WorkflowExecutionContext ctx) {
        String snapshotJson = ctx.snapshotJson();
        String memorySummary = null;
        if (ctx.sessionState() != null && ctx.sessionState().memory() != null
                && ctx.sessionState().memory().hasSummary()) {
            memorySummary = ctx.sessionState().memory().summary();
        }
        checkpointPort.save(run.runId, run.stepIndex, run.status, snapshotJson, memorySummary);
    }

    private String firstLine(String title, String output) {
        String s = output != null ? output : "";
        int nl = s.indexOf('\n');
        String line = nl > 0 ? s.substring(0, nl) : (s.length() > 120 ? s.substring(0, 120) + "..." : s);
        return "[" + title + "] " + line;
    }
}