package de.augmentia.quad.core.workflow.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unveraenderliche Sicht auf einen Lauf, wie REST-Antworten sie ausgeben.
 *
 * <p>Gehoert neben {@link WorkflowRunState}, weil die Frage "was zeigt die
 * API" nicht von der Persistenz abhaengt - der Spring-Boot-Worker hat
 * denselben Bedarf, aber keine {@code RunStore}.
 */
public record WorkflowRunSnapshot(String runId, String workflowId, String status,
                          String startedAt, String finishedAt, long durationMs,
                          List<WorkflowNodeResult> nodeResults, String initialData,
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
        for (WorkflowNodeResult n : nodeResults) {
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

/** Snapshot eines Laufs fuer REST-Antworten; unveraenderlich. */
