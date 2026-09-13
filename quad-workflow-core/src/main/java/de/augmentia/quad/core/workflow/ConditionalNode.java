package de.augmentia.quad.core.workflow;
import de.augmentia.quad.core.scope.AgentScope;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.config.StructuredOutputConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A conditional branching node that routes execution based on a condition.
 * <p>
 * Equivalent to langchain4j-agentic's conditional routing patterns.
 *
 * <pre>{@code
 * workflow.branch("scoreCheck", cfg -> cfg
 *     .evaluate(scope -> ((Double) scope.get("planScore")) >= 0.8, "approved")
 *     .branch("approved").addStep("implementer", implementerAgent).build()
 *     .branch("rejected").addTransform("oldPlan", "newPlan", planner::refine).build());
 * }</pre>
 */
public final class ConditionalNode implements WorkflowNode {

    public enum ConditionType {
        /** Evaluates a predicate directly (lambda). */
        PREDICATE,
        /** Evaluates using an ExitCondition check. */
        EXIT_CONDITION
    }

    private final String nodeId;
    private final ConditionType conditionType;
    private final Object conditionValue; // Predicate<Boolean> or ExitCondition
    private final Map<String, List<WorkflowNode>> branches = new LinkedHashMap<>();
    private List<WorkflowNode> defaultBranch;
    private final String matchedKey;       // Key for predicate/condition TRUE
    private final String notMatchedKey;    // Key for predicate/condition FALSE

    private ConditionalNode(String nodeId, ConditionType conditionType, Object conditionValue,
                            String matchedKey, String notMatchedKey) {
        this.nodeId = nodeId;
        this.conditionType = conditionType;
        this.conditionValue = conditionValue;
        this.matchedKey = matchedKey;
        this.notMatchedKey = notMatchedKey;
    }

    @Override
    public String id() { return nodeId; }

    /**
     * Factory method: creates a new ConditionalNode.Builder.
     */
    public static Builder builder(String nodeId) {
        return new Builder(nodeId);
    }

    public ConditionType conditionType() { return conditionType; }
    public Object conditionValue() { return conditionValue; }
    public Map<String, List<WorkflowNode>> branches() { return Map.copyOf(branches); }
    public List<WorkflowNode> defaultBranch() { return defaultBranch; }
    public String matchedKey() { return matchedKey; }
    public String notMatchedKey() { return notMatchedKey; }

    /**
     * Builder for creating ConditionalNodes inside AgentWorkflowBuilder.branch().
     */
    public static class Builder {
        private final String nodeId;
        private ConditionType conditionType;
        private Object conditionValue;
    private final Map<String, BranchDefinition> branchDefs = new LinkedHashMap<>();
    private List<WorkflowNode> defaultBranch;
    private String matchedKey;      // Key for predicate/condition TRUE
    private String notMatchedKey;   // Key for predicate/condition FALSE

    public Builder(String nodeId) { this.nodeId = nodeId; }

    public Builder evaluate(Function<AgentScope, Boolean> predicate,
                           String trueKey, String falseKey) {
        this.conditionType = ConditionType.PREDICATE;
        this.conditionValue = predicate;
        this.matchedKey = trueKey;
        this.notMatchedKey = falseKey;
        return this;
    }

    public Builder evaluate(ExitCondition exitCondition, String matchedKey, String notMatchedKey) {
        this.conditionType = ConditionType.EXIT_CONDITION;
        this.conditionValue = exitCondition;
        this.matchedKey = matchedKey;
        this.notMatchedKey = notMatchedKey;
        return this;
    }

    public BranchBuilder branch(String key) {
        return new BranchBuilder(this, key);
    }

    /** Adds a list of nodes as the default fallback branch. */
    public Builder defaultBranch(List<WorkflowNode> nodes) {
        this.defaultBranch = nodes;
        return this;
    }

    /** Finishes building and returns the constructed ConditionalNode. */
    public ConditionalNode build() {
        ConditionalNode node = new ConditionalNode(nodeId, conditionType, conditionValue, matchedKey, notMatchedKey);
        for (var entry : branchDefs.entrySet()) {
            node.branches.put(entry.getKey(), entry.getValue().nodes);
        }
        node.defaultBranch = this.defaultBranch;
        return node;
    }
}

/**
 * Fluent builder for defining individual branches within a ConditionalNode.
 */
public static class BranchBuilder {
    private final Builder parent;
    private final String key;
    final List<WorkflowNode> nodes = new ArrayList<>();

    BranchBuilder(Builder parent, String key) {
        this.parent = parent;
        this.key = key;
    }

    public BranchBuilder addNode(WorkflowNode node) {
        nodes.add(node);
        return this;
    }

    public BranchBuilder addStep(Agent agent, StructuredOutputConfig outputConfig, String outputKey) {
        var step = AgentWorkflowBuilder.AgentStep.forAgent(agent, outputConfig, outputKey);
        nodes.add(step);
        return this;
    }

    public BranchBuilder addTransform(Function<Object, Object> fn, String fromKey, String toKey) {
        var transform = AgentWorkflowBuilder.AgentStep.forTransform(fn, fromKey, toKey);
        nodes.add(transform);
        return this;
    }

    /** Registers this branch with the parent builder and returns the parent for further chaining. */
    public Builder build() {
        BranchDefinition def = new BranchDefinition(nodes);
        parent.branchDefs.put(key, def);
        return parent;
    }
}

record BranchDefinition(List<WorkflowNode> nodes) {}
}
