package de.augmentia.quad.quarkus.workflow.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.quarkus.persistence.RunStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central, thread-safe runtime context for workflow runs. Encapsulates the
 * per-run mutable state (node outputs, statuses, join deadlines, session)
 * and passes it around instead of many loose method parameters.
 */
public class WorkflowExecutionContext {

    private final String runId;
    private final RunStore.RunState run;
    private final String initialData;
    private final String fallbackData;
    private final AgentSessionState sessionState;
    private final boolean resume;

    private final ConcurrentHashMap<String, String> outputs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> statuses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> joinDeadline = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> loopIterations = new ConcurrentHashMap<>();

    public WorkflowExecutionContext(String runId, RunStore.RunState run, String initialData,
                                    String fallbackData, AgentSessionState sessionState, boolean resume) {
        this.runId = runId;
        this.run = run;
        this.initialData = initialData != null ? initialData : "";
        this.fallbackData = fallbackData != null ? fallbackData : initialData != null ? initialData : "";
        this.sessionState = sessionState;
        this.resume = resume;
    }

    public String runId() {
        return runId;
    }

    public RunStore.RunState run() {
        return run;
    }

    public String initialData() {
        return initialData;
    }

    public String fallbackData() {
        return fallbackData;
    }

    public AgentSessionState sessionState() {
        return sessionState;
    }

    public boolean resume() {
        return resume;
    }

    public ConcurrentHashMap<String, String> outputs() {
        return outputs;
    }

    public ConcurrentHashMap<String, String> statuses() {
        return statuses;
    }

    public ConcurrentHashMap<String, Long> joinDeadline() {
        return joinDeadline;
    }

    /** Loop node -> current (smallest still running) iteration, persistable for resume. */
    public ConcurrentHashMap<String, Integer> loopIterations() {
        return loopIterations;
    }

    public boolean hasOutput(String nodeId) {
        return outputs.containsKey(nodeId);
    }

    public String output(String nodeId) {
        return outputs.get(nodeId);
    }

    public void putOutput(String nodeId, String value) {
        outputs.put(nodeId, value);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Snapshot schema version. Version 2 (level 12) carries {@code loopIterations}
     * in addition to outputs/statuses/joinDeadline. Old snapshots (without {@code version})
     * fall back to the nodeResults-based restore logic.
     */
    public static final int SNAPSHOT_VERSION = 2;

    /**
     * Serializes the runtime context (outputs, statuses, join deadlines, loop
     * iterations, session summary) as JSON for the step snapshot.
     */
    public String snapshotJson() {
        try {
            var m = new LinkedHashMap<String, Object>();
            m.put("version", SNAPSHOT_VERSION);
            m.put("outputs", new LinkedHashMap<>(outputs));
            m.put("statuses", new LinkedHashMap<>(statuses));
            if (!joinDeadline.isEmpty()) {
                m.put("joinDeadline", new LinkedHashMap<>(joinDeadline));
            }
            if (!loopIterations.isEmpty()) {
                m.put("loopIterations", new LinkedHashMap<>(loopIterations));
            }
            if (sessionState != null) {
                var summary = new LinkedHashMap<String, Object>();
                if (sessionState.findings() != null && !sessionState.findings().isEmpty()) {
                    summary.put("findings", sessionState.findings());
                }
                if (sessionState.memory() != null && sessionState.memory().hasSummary()) {
                    summary.put("memorySummary", sessionState.memory().summary());
                }
                if (!summary.isEmpty()) {
                    m.put("sessionSummary", summary);
                }
            }
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Restores runtime state (outputs, statuses, join deadlines, loop iterations)
     * contained in a persisted snapshot into this context. {@code restore} is applied
     * on re-attach (Continue) after nodeResults-based restores and is a no-op
     * for old snapshots without {@code version}/corresponding fields (fallback).
     *
     * @param commandModel Snapshots are always strings -> {@code outputs}/{@code statuses} are
     *                     interpreted as Map<String,String>, {@code joinDeadline} as Map<String,Number>,
     *                     {@code loopIterations} as Map<String,Number>.
     */
    @SuppressWarnings("unchecked")
    public void restoreSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) return;
        try {
            var m = MAPPER.readValue(snapshotJson, LinkedHashMap.class);
            Object outs = m.get("outputs");
            if (outs instanceof Map<?, ?> om) {
                for (var e : om.entrySet()) outputs.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            Object sts = m.get("statuses");
            if (sts instanceof Map<?, ?> sm) {
                for (var e : sm.entrySet()) statuses.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            Object dl = m.get("joinDeadline");
            if (dl instanceof Map<?, ?> dm) {
                for (var e : dm.entrySet()) {
                    if (e.getValue() instanceof Number n) joinDeadline.put(String.valueOf(e.getKey()), n.longValue());
                }
            }
            Object li = m.get("loopIterations");
            if (li instanceof Map<?, ?> lm) {
                for (var e : lm.entrySet()) {
                    if (e.getValue() instanceof Number n) loopIterations.put(String.valueOf(e.getKey()), n.intValue());
                }
            }
        } catch (Exception e) {
            // Fallback: unreadable snapshot is ignored, nodeResults-based restore remains.
        }
    }
}