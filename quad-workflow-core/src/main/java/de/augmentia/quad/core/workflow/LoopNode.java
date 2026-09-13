package de.augmentia.quad.core.workflow;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.config.StructuredOutputConfig;

import java.util.function.Function;

/**
 * A looping node that executes a sequence of steps repeatedly until
 * the exit condition is met or the maximum number of iterations is reached.
 * <p>
 * Equivalent to langchain4j-agentic's {@code @LoopAgent}.
 *
 * <pre>{@code
 * new AgentWorkflowBuilder().loop("planReview", cfg -> cfg
 *     .addStep("planner", plannerAgent)
 *     .transform("planText", "evaluatedScore", evaluator::score)
 *     .maxIterations(5)
 *     .exitCondition(ExpressionExitCondition.fromExpression("evaluatedScore >= 0.8"))
 *     .outputKey("reviewResult"));
 * }</pre>
 */
public final class LoopNode implements WorkflowNode {

    private final String nodeId;
    private final String outputKey;
    private final int maxIterations;
    private final ExitCondition exitCondition;
    private final LoopSteps loopSteps;
    private final String failureOutputKey;

    private LoopNode(String nodeId, String outputKey, int maxIterations,
                     ExitCondition exitCondition, LoopSteps loopSteps, String failureOutputKey) {
        this.nodeId = nodeId;
        this.outputKey = outputKey;
        this.maxIterations = maxIterations;
        this.exitCondition = exitCondition;
        this.loopSteps = loopSteps;
        this.failureOutputKey = failureOutputKey;
    }

    @Override
    public String id() {
        return nodeId;
    }

    public LoopSteps loopSteps() { return loopSteps; }
    public String outputKey() { return outputKey; }
    public int maxIterations() { return maxIterations; }
    public ExitCondition exitCondition() { return exitCondition; }
    public String failureOutputKey() { return failureOutputKey; }

    /**
     * Factory method: creates a new LoopNode.Builder with default settings.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating LoopNodes inside AgentWorkflowBuilder.loop().
     */
    public static class Builder {
        private String nodeId;
        private String outputKey = "loopOutput";
        private int maxIterations = 5;
        private ExitCondition exitCondition;
        private final LoopSteps loopSteps = new LoopSteps();
        private String failureOutputKey = "loopFailure";

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder outputKey(String outputKey) { this.outputKey = outputKey; return this; }
        public Builder maxIterations(int maxIterations) { this.maxIterations = maxIterations; return this; }
        public Builder exitCondition(ExitCondition exitCondition) { this.exitCondition = exitCondition; return this; }
        public Builder failureOutputKey(String key) { this.failureOutputKey = key; return this; }

        /** Adds an agent step to the loop body. */
        public Builder addStep(Agent agent, StructuredOutputConfig outputConfig, String outputKey) {
            loopSteps.add(agent, outputConfig, outputKey);
            return this;
        }

        /** Adds a transform step to the loop body. */
        public Builder addTransform(Function<Object, Object> transformer,
                                     String fromKey, String toKey) {
            loopSteps.addTransformer(transformer, fromKey, toKey);
            return this;
        }

        public LoopNode build() {
            return new LoopNode(nodeId, outputKey, maxIterations, exitCondition, loopSteps, failureOutputKey);
        }
    }
}
