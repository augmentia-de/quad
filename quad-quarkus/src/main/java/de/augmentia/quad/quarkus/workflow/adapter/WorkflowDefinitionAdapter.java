package de.augmentia.quad.quarkus.workflow.adapter;

import de.augmentia.quad.contract.ApiDtos;
import de.augmentia.quad.quarkus.ui.SharedState;
import de.augmentia.quad.workflow.engine.port.WorkflowDefinitionPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Binds the engine to the in-memory inventory of the workflow definitions.
 *
 * <p>{@code WorkflowEngine} and {@code InputBuilder} previously accessed
 * {@code SharedState.workflows()} directly. But {@code SharedState} collects
 * much more (agents, UI status, metrics) — the engine needed only the
 * definitions. The port makes exactly this excerpt visible.
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