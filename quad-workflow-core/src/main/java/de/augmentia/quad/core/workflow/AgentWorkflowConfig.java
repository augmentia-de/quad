package de.augmentia.quad.core.workflow;

import java.util.ArrayList;
import java.util.List;

/**
 * Complete configuration for an agent workflow, combining sequential steps
 * with control-flow nodes (loops, conditionals, nested workflows).
 * <p>
 * This is the result of building a workflow via {@link AgentWorkflowBuilder}.
 *
 * @see AgentWorkflowBuilder
 */
public final class AgentWorkflowConfig {

    private final String workflowId;
    private final List<AgentWorkflowBuilder.AgentStep> steps;
    private final List<LoopNode> loops;
    private final List<ConditionalNode> conditionals;
    private final List<NestedWorkflowNode> nestedWorkflows;

    private AgentWorkflowConfig(String workflowId,
                                 List<AgentWorkflowBuilder.AgentStep> steps,
                                 List<LoopNode> loops,
                                 List<ConditionalNode> conditionals,
                                 List<NestedWorkflowNode> nestedWorkflows) {
        this.workflowId = workflowId;
        this.steps = List.copyOf(steps);
        this.loops = List.copyOf(loops);
        this.conditionals = List.copyOf(conditionals);
        this.nestedWorkflows = List.copyOf(nestedWorkflows);
    }

    public String workflowId() { return workflowId; }
    public List<AgentWorkflowBuilder.AgentStep> steps() { return steps; }
    public List<LoopNode> loops() { return loops; }
    public List<ConditionalNode> conditionals() { return conditionals; }
    public List<NestedWorkflowNode> nestedWorkflows() { return nestedWorkflows; }
    public boolean hasLoops() { return !loops.isEmpty(); }
    public boolean hasConditionals() { return !conditionals.isEmpty(); }
    public int totalSize() { return steps.size() + loops.size() + conditionals.size() + nestedWorkflows.size(); }

    /**
     * Creates a new config builder. Use this to construct workflow configurations
     * programmatically outside of AgentWorkflowBuilder's fluent API.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for AgentWorkflowConfig. Enables programmatic construction
     * of complex workflows with mixed step types.
     */
    public static class Builder {
        private String workflowId;
        private final List<AgentWorkflowBuilder.AgentStep> steps = new ArrayList<>();
        private final List<LoopNode> loops = new ArrayList<>();
        private final List<ConditionalNode> conditionals = new ArrayList<>();
        private final List<NestedWorkflowNode> nestedWorkflows = new ArrayList<>();

        public Builder workflowId(String id) { this.workflowId = id; return this; }

        public Builder steps(List<AgentWorkflowBuilder.AgentStep> s) {
            this.steps.addAll(s);
            return this;
        }

        public Builder addLoop(LoopNode loop) {
            loops.add(loop);
            return this;
        }

        public Builder addConditional(ConditionalNode conditional) {
            conditionals.add(conditional);
            return this;
        }

        public Builder addNested(NestedWorkflowNode nested) {
            nestedWorkflows.add(nested);
            return this;
        }

        public AgentWorkflowConfig build() {
            return new AgentWorkflowConfig(workflowId != null ? workflowId : "workflow-" + System.currentTimeMillis(),
                steps, loops, conditionals, nestedWorkflows);
        }
    }
}
