package de.augmentia.quad.core.workflow.run;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Zustand eines Workflow-Laufs, framework-neutral.
 *
 * <p>Das Modell ist bewusst feldoffen und fluchbar: die Engine mutiert den
 * Zustand waehrend der Ausfuehrung direkt ({@code run.status = "running"}).
 * Eine Builder-/Setter-Getter-Variante wuerde jeden der ~30 Zugriffsstellen in
 * den Node-Executors umschreiben, ohne Nutzen.
 *
 * <p>Vorher existierte dieser Zustand zweimal: als package-private
 * {@code RunStore.RunState} (Persistenz) und als hier. Der Persistenztyp
 * brauchte einen package-privaten Konstruktor und gehoerte damit zur
 * Quarkus-App - die Engine konnte ihn nicht sehen, und ein Adapter haette
 * Feld fuer Feld kopieren muessen. Jetzt gibt es genau einen Typ, den sowohl
 * die Engine als auch {@code RunStore} benutzen.
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

    /** Run kind: "WORKFLOW" (default) oder "AGENT" - traegt das gemeinsame Run-Modell. */
    public volatile String kind = "WORKFLOW";

    /** Schrittzaehler; Basis fuer deterministisches Resume. */
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

    /** Setzt den Lauf auf "neu starten" zurueck: Status, Schrittzaehler, Ergebnisse. */
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

    /** Unveraenderliche Kopie fuer REST-Ausgaben. */
    public WorkflowRunSnapshot snapshot() {
        return new WorkflowRunSnapshot(runId, workflowId, status, startedAt, finishedAt,
                durationMs, List.copyOf(nodeResults), initialData, executingFrom,
                restartCount, kind, stepIndex);
    }
}
