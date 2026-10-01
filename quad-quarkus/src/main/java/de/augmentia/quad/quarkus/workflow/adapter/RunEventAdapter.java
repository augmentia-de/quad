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
 * Bindet die Engine an die vier Ereignis-Protokolle der Quarkus-App.
 *
 * <p>{@code WorkflowEngine} schrieb vorher direkt in {@code SessionStore},
 * {@code AuditStore}, {@code TelemetryStore} und - fuer den Metrikzaehler -
 * in {@code SharedState}. Vier Beans, nur damit ein Lauf seinen Fortschritt
 * melden kann. Dieser Adapter ist die einzige Stelle, die diese vier kennt.
 *
 * <p>Die Semantik der Aufrufe ist identisch zur vorherigen Direktverdrahtung;
 * umbenannt wurde nur, was jetzt ueber den Port laeuft
 * ({@code recordLifecycle} statt {@code recordRunLifecycle}).
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