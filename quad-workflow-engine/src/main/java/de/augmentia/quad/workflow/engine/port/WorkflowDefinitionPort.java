package de.augmentia.quad.workflow.engine.port;

import de.augmentia.quad.contract.ApiDtos;

/**
 * Port for looking up workflow definitions at runtime.
 *
 * <p>{@code NestedWorkflowNodeExecutor} read the nested definitions from
 * {@code ui.SharedState} — the in-memory UI state of the Quarkus app. That way
 * a nested workflow was only executable as long as the UI module was loaded.
 *
 * <p>The return type is {@link ApiDtos.WorkflowDef}, not {@code Object}:
 * the definition is a contract type from {@code quad-contract} and thus a
 * type a port may name. With {@code Object} every caller would have to
 * cast.
 *
 * <p>Implementierung: {@code WorkflowDefinitionAdapter} in quad-quarkus.
 */
public interface WorkflowDefinitionPort {

    /**
     * Returns a workflow definition by ID or {@code null} if unknown.
     *
     * <p>{@code null} is an expected return value: a NestedWorkflowNode
     * references an ID that may have been deleted or never deployed. The
     * engine treats that as a node error, not as an exception.
     */
    ApiDtos.WorkflowDef find(String workflowId);
}