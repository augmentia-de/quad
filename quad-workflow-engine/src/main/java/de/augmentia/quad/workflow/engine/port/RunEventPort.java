package de.augmentia.quad.workflow.engine.port;

import de.augmentia.quad.core.session.AgentSessionState;

import java.time.Instant;

/**
 * Port fuer Ereignis-, Audit- und Telemetrie-Aufzeichnung eines Laufs.
 *
 * <p>Bildet die bisherigen Direktaufrufe von {@code SessionStore},
 * {@code AuditStore}, {@code TelemetryStore} und {@code SharedState.incMetrics}
 * ab. Das sind vier Append-only-Protokolle ueber denselben Lauf plus ein
 * Zaehler - aus Sicht der Engine ein einziger Concern. Der Port heisst
 * bewusst nicht "Persistence": das wuerde einen Port pro Tabelle suggerieren
 * und die Engine wieder an das Schema der App koppeln.
 *
 * <p>Die Signaturen folgen exakt den bisherigen Aufrufen in
 * {@code WorkflowEngine}, damit der Umbau keine Verhaltens-aenderung erzwingt.
 *
 * <p>Implementierung: {@code RunEventAdapter} in quad-quarkus.
 */
public interface RunEventPort {

    /**
     * Haengt ein Ereignis an die Session-Historie des Laufs.
     *
     * <p>Aufrufer: Abschluss, Fehlerfall und Node-Timeouts.
     *
     * @param runId     Lauf
     * @param eventType z. B. {@code RUN_COMPLETED}, {@code NODE_TIMED_OUT}
     * @param payloadJson Zusatzinformation als JSON
     */
    void recordEvent(String runId, String eventType, String payloadJson);

    /**
     * Audit-Eintrag: wer hat was wann ausgeloest.
     *
     * <p>Die ID-Erzeugung bleibt bei der Engine, damit der Audit-Trail
     * durchgaengig dieselbe Lauf-ID traegt.
     */
    void audit(String id, String timestamp, String agent, String event, String details);

    /**
     * Schreibt den Session-Zustand des Laufs (Task, Ergebnis, Memory-Summary).
     *
     * @param memorySummary {@code null}, wenn die Session keine Zusammenfassung hat
     * @param sessionState  vollstaendige Session fuer den Fortschritt; {@code null} ist erlaubt
     */
    void upsertRunSession(String runId, String task, String result,
                          String memorySummary, AgentSessionState sessionState);

    /**
     * Lebenszyklus-Metrik (start, resume, completed, failed).
     *
     * @param phase    {@code RUN_STARTED}, {@code RUN_RESUMED}, ...
     * @param timestamp Zeitpunkt des Ereignisses
     */
    void recordLifecycle(String runId, String phase, Instant timestamp);

    /**
     * Schritt-Metrik fuer Observability-Dashboards.
     *
     * @param stepId   technische Schritt-ID
     * @param kind     Knotentyp
     * @param status   Ergebnisstatus
     * @param durMs    Dauer in Millisekunden
     */
    void recordStep(String runId, String stepId, String kind, String status, long durMs);

    /**
     * Laufzaehler fuer die UI-Metrik. Gab vorher {@code SharedState.incMetrics()};
     * die Engine brauchte dafuer die komplette {@code SharedState}-Bean.
     *
     * @return neuer Zaehlerstand
     */
    long incrementRunCounter();
}