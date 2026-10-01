package de.augmentia.quad.workflow.engine.node;

import de.augmentia.quad.workflow.engine.internal.WorkflowExecutionContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strategy for a workflow node type. A new node type means merely a new
 * implementation of {@link NodeExecutor}, registered in the
 * {@code NodeExecutorRegistry} - the {@code WorkflowEngine} stays unchanged.
 */
public interface NodeExecutor {

    /** Returns the node types this executor serves (e.g. "loop", "conditional", "join"). */
    Set<String> getTypes();

    /**
     * Executes a specific node.
     *
     * @return true if the node has written an output in {@code ctx} and is considered
     *         completed; false if it is still waiting for asynchronous completion.
     */
    boolean execute(Map<String, Object> node, List<Map<String, Object>> allNodes,
                    List<Map<String, Object>> edges, WorkflowExecutionContext ctx, NodeRunner runner);
}