package de.augmentia.quad.workflow.engine.port;

import de.augmentia.quad.core.workflow.run.WorkflowRunState;
import java.util.List;

/**
 * Persistenz-Port fuer die Workflow-Ausfuehrung.
 *
 * <p>Die Engine kennt bewusst weder Quarkus noch Panache noch JDBC. Vorher
 * sprach {@code WorkflowEngine} direkt mit {@code RunStore} - dadurch war die
 * Engine nur innerhalb der Quarkus-App testbar und fuer den Spring-Boot-Worker
 * unbrauchbar. Diese Schnittstelle ist der Schnitt.
 *
 * <p>Implementierung: {@code RunStoreAdapter} in quad-quarkus.
 *
 * <h3>Warum RunState nicht im Interface steckt</h3>
 * <p>{@code RunState} war eine innere Klasse von {@code RunStore} und wird von
 * der Engine als Arbeitsspeicher mutiert. Ein Interface kann keine innere Klasse
 * zurueckgeben, ohne dass der Aufrufer sie nie sehen darf. Die Engine braucht
 * den Zustand aber. Deshalb ist {@link WorkflowRunState} ein eigenes
 * framework-neutrales Modell; der Adapter mappt RunState darauf.
 */
public interface WorkflowRunPort {

    /**
     * Legt einen neuen Lauf an und gibt seinen Zustand zurueck.
     *
     * @param workflowId  ID des gestarteten Workflows
     * @param nodes       Knoten-IDs in Ausfuehrungsreihenfolge
     * @param initialData initialer Kontext, serialisiert
     */
    WorkflowRunState create(String workflowId, List<String> nodes, String initialData);

    /**
     * Laedt einen bestehenden Lauf. Liefert {@code null}, wenn unbekannt -
     * das ist ein regulaerer Fall (abgelaufener Run, fremde runId), kein Fehler.
     */
    WorkflowRunState load(String runId);

    /**
     * Schreibt den Zustand zurueck. Wird nach jedem abgeschlossenen Schritt
     * gerufen, damit ein Neustart den Lauf an der richtigen Stelle fortsetzt.
     */
    void save(WorkflowRunState state);
}