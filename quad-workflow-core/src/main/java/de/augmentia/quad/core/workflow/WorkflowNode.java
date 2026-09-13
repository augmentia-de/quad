package de.augmentia.quad.core.workflow;

/**
 * Base interface for all workflow node types in quad-core.
 * <p>
 * Nodes can be:
 * - {@link AgentWorkflowBuilder.AgentStep} — a single agent execution step (sequential)
 * - {@link LoopNode} — a loop that repeats steps until an exit condition is met
 * - {@link ConditionalNode} — a branch that routes based on a predicate
 * - {@link NestedWorkflowNode} — a nested workflow invocation
 *
 * @see AgentWorkflowBuilder
 */
public interface WorkflowNode {

    /** Unique identifier of this node within its parent workflow. */
    String id();
}
