package de.augmentia.quad.quarkus.persistence;

import de.augmentia.quad.core.events.AgentEvent;
import de.augmentia.quad.core.events.AgentEventListener;
import de.augmentia.quad.core.events.ModelRequestedEvent;
import de.augmentia.quad.core.events.ToolExecutionFinishedEvent;
import de.augmentia.quad.core.events.ToolExecutionStartedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase-A exporter (level 13): observes agent events and persists tool/model steps
 * as {@code STEP}-Records in the telemetry ({@code session_events}).
 *
 * <p>Attaches as {@link AgentEventListener} to the {@code AgentEventPublisher} on agents and is
 * attached to every agent built in {@code ChannelAgentFactory}. No modification to
 * {@code AgentRuntime}/{@code QuadAgent} - pure post-hoc observability.
 *
 * <p>Idempotency: one tool call (start+finish) produces exactly one STEP-Record (on finish).
 * A monotonically increasing sequence counter is maintained per session, so repeated tool calls
 * within an agent run receive distinct {@code stepId}s.
 * Model-Steps are recorded on {@link ModelRequestedEvent} (one record per LLM-Call).
 */
@ApplicationScoped
public class AgentStepExporter implements AgentEventListener {

    private record InFlight(Instant startedAt, String stepId) {}

    private final ConcurrentMap<String, ConcurrentMap<String, InFlight>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> sessionCounters = new ConcurrentHashMap<>();

    @Inject
    TelemetryStore telemetryStore;

    public AgentStepExporter() {
    }

    /** For tests: direct wiring without CDI. */
    public void setTelemetryStore(TelemetryStore telemetryStore) {
        this.telemetryStore = telemetryStore;
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (event instanceof ToolExecutionStartedEvent s) {
            toolStarted(s.sessionId(), s.toolExecutionRequest().name());
        } else if (event instanceof ToolExecutionFinishedEvent f) {
            toolFinished(f.sessionId(), f.toolName(), f.isError(), f.result(), f.timestamp());
        } else if (event instanceof ModelRequestedEvent m) {
            modelRequested(m.sessionId());
        }
    }

    void toolStarted(String sessionId, String toolName) {
        if (sessionId == null || sessionId.isBlank() || toolName == null || toolName.isBlank()) return;
        String stepId = "tool-" + nextSeq(sessionId);
        inFlight.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(toolName, new InFlight(Instant.now(), stepId));
    }

    void toolFinished(String sessionId, String toolName, boolean isError, String result, Instant finishedAt) {
        if (sessionId == null || sessionId.isBlank() || toolName == null || toolName.isBlank()) return;
        var sessions = inFlight.get(sessionId);
        if (sessions == null) {
            return;
        }
        InFlight start = sessions.remove(toolName);
        long durMs = 0;
        if (start != null && start.startedAt() != null && finishedAt != null) {
            durMs = Duration.between(start.startedAt(), finishedAt).toMillis();
        }
        String stepId = start != null ? start.stepId() : "tool-" + nextSeq(sessionId);
        String status = isError ? "failed" : "completed";
        persist(sessionId, stepId, "tool", status, durMs);
    }

    void modelRequested(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        persist(sessionId, "model-" + nextSeq(sessionId), "model", "started", 0);
    }

    private void persist(String sessionId, String stepId, String kind, String status, long durMs) {
        TelemetryStore store = telemetryStore;
        if (store == null) return;
        store.recordStep(sessionId, stepId, kind, status, durMs);
    }

    private long nextSeq(String sessionId) {
        return sessionCounters.computeIfAbsent(sessionId, k -> new AtomicLong()).incrementAndGet();
    }

    /** Unused run counter (reserved for later step-resume in phase B). */
    void reset() {
        inFlight.clear();
        sessionCounters.clear();
    }
}
