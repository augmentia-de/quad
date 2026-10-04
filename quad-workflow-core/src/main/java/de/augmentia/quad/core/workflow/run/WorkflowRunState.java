package de.augmentia.quad.core.workflow.run;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * State of a workflow run, framework-neutral.
 *
 * <p>The model is deliberately open and mutable: the engine mutates the
 * state directly during execution ({@code run.status = "running"}).
 * A builder/setter-getter variant would require changing each of the ~30 access points
 * that the node executors write, without benefit.
 *
 * <p>Previously this state existed twice: as a package-private
 * {@code RunStore.RunState} (persistence) and as here. The persistence type
 * needed a package-private constructor and thus belonged to the
 * Quarkus app - the engine could not see it, and an adapter would have to map
 * field by field. Now there is exactly one type that both
 * the engine and {@code RunStore} use.
 */
public class WorkflowRunState {

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

    public final List<WorkflowNodeResult> nodeResults = new CopyOnWriteArrayList<>();

    public WorkflowRunState(String runId, String workflowId, String startedAt) {
        this.runId = runId;
        this.workflowId = workflowId;
        this.startedAt = startedAt;
    }

    public static WorkflowRunState start(String runId, String workflowId) {
        return new WorkflowRunState(runId, workflowId, java.time.Instant.now().toString());
    }

    /** Resets the run to "restart": status, step counter, results. */
    public void restart() {
        this.restartCount++;
        this.status = "running";
        this.finishedAt = null;
        this.durationMs = 0;
        this.executingFrom = null;
        this.stepIndex = 0;
        nodeResults.replaceAll(r -> new WorkflowNodeResult(
                r.nodeId(), r.type(), r.title(), "pending", "", ""));
    }

    /** Immutable copy for REST output. */
    public WorkflowRunSnapshot snapshot() {
        return new WorkflowRunSnapshot(runId, workflowId, status, startedAt, finishedAt,
                durationMs, List.copyOf(nodeResults), initialData, executingFrom,
                restartCount, kind, stepIndex);
    }
}
