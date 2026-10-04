package de.augmentia.quad.quarkus.workflow.adapter;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.quarkus.persistence.AuditStore;
import de.augmentia.quad.quarkus.persistence.SessionStore;
import de.augmentia.quad.quarkus.persistence.TelemetryStore;
import de.augmentia.quad.quarkus.ui.SharedState;
import de.augmentia.quad.workflow.engine.port.RunEventPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;

/**
 * Binds the engine to the four event logs of the Quarkus app.
 *
 * <p>{@code WorkflowEngine} previously wrote directly to {@code SessionStore},
 * {@code AuditStore}, {@code TelemetryStore} and - for the metric counter -
 * to {@code SharedState}. Four beans purely so that a run can report its progress
 * somehow. This adapter is the only place that knows these four.
 *
 * <p>The semantics of the calls are identical to the previous direct wiring;
 * only what now runs via the port was renamed
 * ({@code recordLifecycle} instead of {@code recordRunLifecycle}).
 */
@ApplicationScoped
public class RunEventAdapter implements RunEventPort {

    private final SessionStore sessionStore;
    private final AuditStore auditStore;
    private final TelemetryStore telemetryStore;
    private final SharedState state;

    @Inject
    public RunEventAdapter(SessionStore sessionStore, AuditStore auditStore,
                           TelemetryStore telemetryStore, SharedState state) {
        this.sessionStore = sessionStore;
        this.auditStore = auditStore;
        this.telemetryStore = telemetryStore;
        this.state = state;
    }

    @Override
    public void recordEvent(String runId, String eventType, String payloadJson) {
        sessionStore.recordEvent(runId, eventType, payloadJson);
    }

    @Override
    public void audit(String id, String timestamp, String agent, String event, String details) {
        auditStore.add(id, timestamp, agent, event, details);
    }

    @Override
    public void upsertRunSession(String runId, String task, String result,
                                 String memorySummary, AgentSessionState sessionState) {
        if (sessionState == null) {
            sessionStore.upsertSession(runId, task, result, memorySummary);
        } else {
            sessionStore.upsertSession(runId, task, result, memorySummary, sessionState);
        }
    }

    @Override
    public void recordLifecycle(String runId, String phase, Instant timestamp) {
        telemetryStore.recordRunLifecycle(runId, phase, timestamp);
    }

    @Override
    public void recordStep(String runId, String stepId, String kind, String status, long durMs) {
        telemetryStore.recordStep(runId, stepId, kind, status, durMs);
    }

    @Override
    public long incrementRunCounter() {
        return state.incMetrics();
    }
}