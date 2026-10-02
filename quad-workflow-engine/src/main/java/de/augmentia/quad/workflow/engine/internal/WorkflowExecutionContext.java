package de.augmentia.quad.workflow.engine.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.workflow.run.WorkflowRunState;
import de.augmentia.quad.workflow.engine.port.WorkflowRunPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central, thread-safe runtime context for workflow runs. Encapsulates the
 * per-run mutable state (node outputs, statuses, join deadlines, session)
 * and passes it around instead of many loose method parameters.
 */
public class WorkflowExecutionContext {

private final String runId;
    private final WorkflowRunState run;
    private final String initialData;
    private final String fallbackData;
    private final AgentSessionState sessionState;
    private final boolean resume;
    /** Static cache prefix (Block A) shared across all agent nodes of this run.
     *  Byte-identical content so the provider's prefix-cache serves it on every
     *  node call. Null/blank = no prefix (no behavior change).
     *  With the agent-scoped chain this seeds the run's current prefix. */
    private final String cachePrefix;
    /**
     * Opt-in marker ("use prefix"). When true the run applies a prefix
     * to every agent node (fallback when a node does not set {@code usePrefix}
     * explicitly) and enforces byte-identical native tools across all
     * prefix-sharing agent nodes (the user is responsible for identical tool
     * registration; the engine fails fast otherwise).
     */
    private final boolean prefixCaching;

    /** Current cache prefix of the agent-scoped chain, advanced as nodes execute.
     *  A node may set a new prefix (prefixSource / cachePrefix), skip (leave as is)
     *  or clear it (clearPrefix). Seeded from the static {@link #cachePrefix} when
     *  prefix caching is active. */
    private volatile String currentPrefix;

    /** Prefix text -> first-seen native tool fingerprint of a prefix-caching run
     *  (guard reference per prefix group). */
    private final ConcurrentHashMap<String, String> sharedToolFingerprints = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, String> outputs = new ConcurrentHashMap<>();
    /**
     * Per-node history of produced values, tagged with the loop iteration that produced them.
     *
     * <p>{@link #outputs} keeps only the last value, and {@code LoopNodeExecutor} clears it before
     * every iteration. A node therefore cannot see what a sibling produced in the previous round —
     * the capability an agent loop needs in order to react to reviewer feedback.
     */
    private final ConcurrentHashMap<String, List<OutputRecord>> outputHistory = new ConcurrentHashMap<>();

    /** Cap per node so a long run cannot grow the history without bound. */
    private final int maxHistoryEntries;
    private final ConcurrentHashMap<String, String> statuses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> joinDeadline = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> loopIterations = new ConcurrentHashMap<>();

    /**
     * Convenience constructor without a cache prefix (no shared static prefix).
     * Kept for callers/tests that do not participate in prefix caching.
     */
    public WorkflowExecutionContext(String runId, WorkflowRunState run, String initialData,
                                     String fallbackData, AgentSessionState sessionState, boolean resume) {
        this(runId, run, initialData, fallbackData, sessionState, resume, null, false);
    }

    /**
     * One recorded node output.
     *
     * @param iteration loop iteration that produced the value; 0 outside a loop
     * @param nodeId    node that produced it
     * @param value     the output text
     */
    public record OutputRecord(int iteration, String nodeId, String value) {}

    /**
     * Full constructor with a shared cache prefix (Block A) across all agent
     * nodes of this run. Byte-identical content so the provider's prefix-cache
     * serves it on every node call. Null/blank = no prefix (no behavior change);
     * {@code prefixCaching} true additionally enforces identical native tools
     * across agent nodes.
     */
    public WorkflowExecutionContext(String runId, WorkflowRunState run, String initialData,
                                     String fallbackData, AgentSessionState sessionState, boolean resume,
                                     String cachePrefix, boolean prefixCaching) {
        this.runId = runId;
        this.run = run;
        this.initialData = initialData != null ? initialData : "";
        this.fallbackData = fallbackData != null ? fallbackData : initialData != null ? initialData : "";
        this.sessionState = sessionState;
        this.resume = resume;
        this.cachePrefix = cachePrefix;
        this.prefixCaching = prefixCaching;
        this.currentPrefix = prefixCaching ? cachePrefix : null;
        this.maxHistoryEntries = 10;
    }

    public String runId() {
        return runId;
    }

    public WorkflowRunState run() {
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

    /** Static cache prefix (Block A) shared across all agent nodes of this run. */
    public String cachePrefix() {
        return cachePrefix;
    }

    /** "Use prefix" marker: prefix applied and identical-tools invariant enforced. */
    public boolean prefixCaching() {
        return prefixCaching;
    }

    /** Current prefix of the agent-scoped chain (may be null when none/cleared). */
    public String currentPrefix() {
        return currentPrefix;
    }

    /** Advances the agent-scoped chain to the given prefix for following nodes. */
    public void setCurrentPrefix(String prefix) {
        this.currentPrefix = prefix;
    }

    /** Deactivates the chain: clears the current prefix for all following nodes. */
    public void clearCurrentPrefix() {
        this.currentPrefix = null;
    }

    /**
     * Guard per prefix group: records the first node's native tool fingerprint for a
     * given prefix text and throws if any later node sharing the exact same prefix
     * diverges. Nodes with a different or no prefix are independent groups. With
     * {@code prefixCaching=true} the user is responsible for identical tool
     * registration; this makes a silent cache degradation fail fast at the
     * divergent node.
     */
    public void assertSharedToolFingerprint(String prefix, String nodeId, String fingerprint) {
        if (prefix == null || prefix.isBlank()) return;
        String ref = sharedToolFingerprints.get(prefix);
        if (ref == null) {
            sharedToolFingerprints.putIfAbsent(prefix, fingerprint);
            return;
        }
        if (!ref.equals(fingerprint)) {
            throw new IllegalStateException(
                    "prefix group '" + prefix + "' — agent node '" + nodeId
                            + "' exposes different native tools than the other nodes sharing this "
                            + "prefix — identical tool registration (same names, descriptions, parameter "
                            + "schemas, same order) is required to keep the shared prefix cacheable.");
        }
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

    /**
     * Records a node output and appends it to that node's history.
     *
     * <p>The history entry is written before {@code outputs} is overwritten so a node reading its
     * own previous value during the same iteration still sees the last one under {@code last}.
     */
    public void putOutput(String nodeId, String value) {
        int iteration = loopIterations.getOrDefault(nodeId, 0);
        outputHistory.compute(nodeId, (k, list) -> {
            List<OutputRecord> target = list == null ? new ArrayList<>() : list;
            target.add(new OutputRecord(iteration, nodeId, value));
            // Keep the tail: the most recent entries are the ones a loop needs.
            while (target.size() > maxHistoryEntries) {
                target.remove(0);
            }
            return target;
        });
        outputs.put(nodeId, value);
    }

    /** Immutable copy of one node's output history, oldest first. */
    public List<OutputRecord> outputHistory(String nodeId) {
        List<OutputRecord> list = outputHistory.get(nodeId);
        return list == null ? List.of() : List.copyOf(list);
    }

    /**
     * Resolves a history query.
     *
     * @param nodeId  node to read
     * @param scope   {@code last} = current value, {@code previousIteration} = newest entry from a
     *                strictly lower iteration, {@code all} = whole history,
     *                {@code iteration:N} = newest entry of exactly that iteration
     */
    public List<String> resolveHistory(String nodeId, String scope) {
        if (scope == null || scope.isBlank() || "last".equals(scope)) {
            String v = outputs.get(nodeId);
            return v == null ? List.of() : List.of(v);
        }
        List<OutputRecord> history = outputHistory(nodeId);
        String s = scope.trim();
        if (s.startsWith("iteration:")) {
            try {
                int wanted = Integer.parseInt(s.substring("iteration:".length()).trim());
                return history.stream()
                        .filter(r -> r.iteration() == wanted)
                        .map(OutputRecord::value)
                        .toList();
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        int currentIteration = loopIterations.getOrDefault(nodeId, 0);
        if ("previousIteration".equals(s)) {
            return history.stream()
                    .filter(r -> r.iteration() < currentIteration)
                    .reduce((a, b) -> b)
                    .map(r -> List.of(r.value()))
                    .orElse(List.of());
        }
        if ("all".equals(s)) {
            return history.stream().map(OutputRecord::value).toList();
        }
        String v = outputs.get(nodeId);
        return v == null ? List.of() : List.of(v);
    }

    /** Removes the current value of a node but keeps its history. */
    public void clearOutput(String nodeId) {
        outputs.remove(nodeId);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Snapshot schema version. Version 2 carries {@code loopIterations}; version 3 adds
     * {@code outputHistory}, without which a resumed run loses the previous loop iteration's values.
     * Older snapshots still restore: the history is then empty and behaviour matches a first run.
     */
    public static final int SNAPSHOT_VERSION = 3;

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
            if (!outputHistory.isEmpty()) {
                var history = new LinkedHashMap<String, List<Map<String, Object>>>();
                for (var e : outputHistory.entrySet()) {
                    List<Map<String, Object>> entries = new ArrayList<>();
                    for (OutputRecord r : e.getValue()) {
                        Map<String, Object> rec = new LinkedHashMap<>();
                        rec.put("iteration", r.iteration());
                        rec.put("nodeId", r.nodeId());
                        rec.put("value", r.value());
                        entries.add(rec);
                    }
                    history.put(e.getKey(), entries);
                }
                m.put("outputHistory", history);
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
            Object oh = m.get("outputHistory");
            if (oh instanceof Map<?, ?> om) {
                for (var e : om.entrySet()) {
                    if (!(e.getValue() instanceof List<?> list)) continue;
                    List<OutputRecord> restored = new ArrayList<>();
                    for (Object o : list) {
                        if (!(o instanceof Map<?, ?> rec)) continue;
                        Object it = rec.get("iteration");
                        Object nid = rec.get("nodeId");
                        Object val = rec.get("value");
                        restored.add(new OutputRecord(
                                it instanceof Number n ? n.intValue() : 0,
                                nid != null ? String.valueOf(nid) : String.valueOf(e.getKey()),
                                val != null ? String.valueOf(val) : ""));
                    }
                    outputHistory.put(String.valueOf(e.getKey()), restored);
                }
            }
        } catch (Exception e) {
            // Fallback: unreadable snapshot is ignored, nodeResults-based restore remains.
        }
    }
}