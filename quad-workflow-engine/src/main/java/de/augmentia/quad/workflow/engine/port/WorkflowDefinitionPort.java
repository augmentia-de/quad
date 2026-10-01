package de.augmentia.quad.workflow.engine.port;

import de.augmentia.quad.contract.ApiDtos;

/**
 * Port fuer den Nachschlag von Workflow-Definitionen zur Laufzeit.
 *
 * <p>{@code NestedWorkflowNodeExecutor} las die verschachtelten Definitionen aus
 * {@code ui.SharedState} - dem In-Memory-UI-Zustand der Quarkus-App. Damit war
 * ein verschachtelter Workflow nur ausfuehrbar, solange das UI-Modul geladen war.
 *
 * <p>Der Rueckgabetyp ist {@link ApiDtos.WorkflowDef}, nicht {@code Object}:
 * die Definition ist ein Vertragstyp aus {@code quad-contract} und damit ein
 * Typ, den ein Port nennen darf. Mit {@code Object} muesste jeder Aufrufer
 * casten.
 *
 * <p>Implementierung: {@code WorkflowDefinitionAdapter} in quad-quarkus.
 */
public interface WorkflowDefinitionPort {

    /**
     * Liefert eine Workflow-Definition per ID oder {@code null}, wenn unbekannt.
     *
     * <p>{@code null} ist ein erwarteter Rueckgabewert: ein Nested-WorkflowNode
     * verweist auf eine ID, die geloescht oder nie deployed worden sein kann. Die
     * Engine behandelt das als Knoten-Fehler, nicht als Exception.
     */
    ApiDtos.WorkflowDef find(String workflowId);
}