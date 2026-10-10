package de.augmentia.quad.core.workflow.run.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Current state of a run, folded from its event log. Immutable.
 *
 * <p>The event log is the source of truth; this projection is rebuilt deterministically
 * (same events ⇒ same projection), which keeps replay and resume honest.</p>
 */
public final class WorkflowRunProjection {

    private final String runId;
    private final String status;
    private final Map<String, String> nodeStatus;
    private final Set<String> pendingApprovals;
    private final List<String> overrideNotes;

    private WorkflowRunProjection(String runId, String status, Map<String, String> nodeStatus,
                                  Set<String> pendingApprovals, List<String> overrideNotes) {
        this.runId = runId;
        this.status = status;
        this.nodeStatus = Collections.unmodifiableMap(nodeStatus);
        this.pendingApprovals = Collections.unmodifiableSet(pendingApprovals);
        this.overrideNotes = Collections.unmodifiableList(overrideNotes);
    }

    public static WorkflowRunProjection of(String runId, List<WorkflowRunEvent> events) {
        String status = "pending";
        Map<String, String> nodes = new LinkedHashMap<>();
        Set<String> pending = new LinkedHashSet<>();
        List<String> overrides = new ArrayList<>();

        if (events != null) {
            for (WorkflowRunEvent e : events) {
                switch (e.type()) {
                    case RUN_STARTED -> status = "running";
                    case NODE_STARTED -> setNode(nodes, e, "running");
                    case NODE_COMPLETED -> setNode(nodes, e, "completed");
                    case NODE_FAILED -> setNode(nodes, e, "failed");
                    case WAITING_HITL -> {
                        setNode(nodes, e, "waiting_hitl");
                        if (!e.isRunLevel()) pending.add(e.nodeId());
                    }
                    case APPROVED -> resolve(nodes, pending, e, "approved");
                    case REJECTED -> resolve(nodes, pending, e, "rejected");
                    case EXPIRED -> resolve(nodes, pending, e, "expired");
                    case ESCALATED -> resolve(nodes, pending, e, "escalated");
                    case OVERRIDES_APPLIED -> overrides.add(e.reason() != null ? e.reason() : "");
                    case RUN_COMPLETED -> status = "completed";
                    case RUN_FAILED -> status = "failed";
                    case RUN_CANCELLED -> status = "cancelled";
                }
            }
        }
        return new WorkflowRunProjection(runId, status, nodes, pending, overrides);
    }

    private static void setNode(Map<String, String> nodes, WorkflowRunEvent e, String value) {
        if (!e.isRunLevel()) {
            nodes.put(e.nodeId(), value);
        }
    }

    private static void resolve(Map<String, String> nodes, Set<String> pending, WorkflowRunEvent e, String outcome) {
        if (!e.isRunLevel()) {
            pending.remove(e.nodeId());
            nodes.put(e.nodeId(), outcome);
        }
    }

    public String runId() {
        return runId;
    }

    public String status() {
        return status;
    }

    public Map<String, String> nodeStatus() {
        return nodeStatus;
    }

    public Set<String> pendingApprovals() {
        return pendingApprovals;
    }

    public List<String> overrideNotes() {
        return overrideNotes;
    }

    public String statusOf(String nodeId) {
        return nodeStatus.get(nodeId);
    }

    public boolean hasPendingApproval(String nodeId) {
        return pendingApprovals.contains(nodeId);
    }
}
