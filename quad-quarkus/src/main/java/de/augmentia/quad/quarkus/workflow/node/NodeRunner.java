package de.augmentia.quad.quarkus.workflow.node;

import de.augmentia.quad.quarkus.workflow.internal.WorkflowExecutionContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Callback by which the scheduler/executor runs a single workflow node.
 * Enables recursive calls (Loop calls body nodes, Conditional calls segment nodes,
 * Nested calls sub-workflow nodes) without direct coupling to the WorkflowEngine.
 */
@FunctionalInterface
public interface NodeRunner {

    /**
     * Executes a single node and writes its output in {@code ctx}.
     *
     * @param node       the node to execute
     * @param allNodes   all nodes of the (sub-)workflow
     * @param edges      all edges of the (sub-)workflow
     * @param ctx        the runtime context
     * @param skip       node ids to skip (Restore/Continue)
     */
    void runNode(Map<String, Object> node, List<Map<String, Object>> allNodes, List<Map<String, Object>> edges,
                 WorkflowExecutionContext ctx, Set<String> skip);
}