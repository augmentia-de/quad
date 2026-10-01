package de.augmentia.quad.quarkus.workflow.adapter;

import de.augmentia.quad.contract.ApiDtos;
import de.augmentia.quad.quarkus.ui.SharedState;
import de.augmentia.quad.workflow.engine.port.WorkflowDefinitionPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Bindet die Engine an den In-Memory-Bestand der Workflow-Definitionen.
 *
 * <p>{@code WorkflowEngine} und {@code InputBuilder} griffen vorher direkt auf
 * {@code SharedState.workflows()} zu. {@code SharedState} sammelt aber sehr
 * viel mehr (Agents, UI-Status, Metriken) - die Engine brauchte davon nur die
 * Definitionen. Der Port macht genau diesen Ausschnitt sichtbar.
 */
@ApplicationScoped
public class WorkflowDefinitionAdapter implements WorkflowDefinitionPort {

    private final SharedState state;

    @Inject
    public WorkflowDefinitionAdapter(SharedState state) {
        this.state = state;
    }

    @Override
    public ApiDtos.WorkflowDef find(String workflowId) {
        if (workflowId == null) return null;
        return state.workflows().get(workflowId);
    }
}