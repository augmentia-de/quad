package de.augmentia.quad.workflow.engine.port;

import de.augmentia.quad.core.session.AgentSessionState;

import java.time.Instant;

/**
 * Port for the event, audit and telemetry recording of a run.
 *
 * <p>Maps the previous direct calls to {@code SessionStore},
 * {@code AuditStore}, {@code TelemetryStore} and {@code SharedState.incMetrics}.
 * That is four append-only logs over the same run plus a
 * counter — from the engine's point of view a single concern. The port is
 * deliberately not called "persistence": that would suggest one port per table
 * and couple the engine to the schema of the app again.
 *
 * <p>The signatures follow exactly the previous calls in
 * {@code WorkflowEngine}, so that the refactoring forces no behaviour change.
 *
 * <p>Implementierung: {@code RunEventAdapter} in quad-quarkus.
 */
public interface RunEventPort {

    /**
     * Appends an event to the session history of the run.
     *
     * <p>Callers: completion, error case and node timeouts.
     *
     * @param runId     run
     * @param eventType e.g. {@code RUN_COMPLETED}, {@code NODE_TIMED_OUT}
     * @param payloadJson additional information as JSON
     */
    void recordEvent(String runId, String eventType, String payloadJson);

    /**
     * Audit entry: who triggered what and when.
     *
     * <p>ID generation stays with the engine, so that the audit trail
     * consistently carries the same run ID.
     */
    void audit(String id, String timestamp, String agent, String event, String details);

    /**
     * Writes the session state of the run (task, result, memory summary).
     *
     * @param memorySummary {@code null} if the session has no summary
     * @param sessionState  complete session for the progress; {@code null} is allowed
     */
    void upsertRunSession(String runId, String task, String result,
                          String memorySummary, AgentSessionState sessionState);

    /**
     * Lifecycle metric (start, resume, completed, failed).
     *
     * @param phase    {@code RUN_STARTED}, {@code RUN_RESUMED}, ...
     * @param timestamp point in time of the event
     */
    void recordLifecycle(String runId, String phase, Instant timestamp);

    /**
     * Step metric for observability dashboards.
     *
     * @param stepId   technical step ID
     * @param kind     node type
     * @param status   result status
     * @param durMs    duration in milliseconds
     */
    void recordStep(String runId, String stepId, String kind, String status, long durMs);

    /**
     * Run counter for the UI metric. Previously {@code SharedState.incMetrics()};
     * the engine needed the complete {@code SharedState} bean for it.
     *
     * @return new counter value
     */
    long incrementRunCounter();
}