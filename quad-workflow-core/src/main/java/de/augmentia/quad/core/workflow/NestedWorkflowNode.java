package de.augmentia.quad.core.workflow;

import java.util.Map;

/**
 * A node that invokes a sub-workflow, allowing nested workflow compositions.
 * <p>
 * Supports input mapping from the parent scope to the sub-workflow's scope,
 * and output mapping from the sub-workflow back to the parent scope.
 *
 * <pre>{@code
 * workflow.addNode("nestedReview", NestedWorkflowNode.builder()
 *     .nodeId("nestedReview")
 *     .workflowId("planReviewWorkflow")
 *     .inputMapping(Map.of("planText", "reviewInput"))
 *     .outputMapping(Map.of("reviewResult", "evaluation"))
 *     .build());
 * }</pre>
 */
public final class NestedWorkflowNode implements WorkflowNode {

    private final String nodeId;
    private final String workflowId;
    private final Map<String, String> inputMapping;  // parentScopeKey -> subWorkflowKey
    private final Map<String, String> outputMapping; // subWorkflowKey -> parentScopeKey

    private NestedWorkflowNode(String nodeId, String workflowId,
                               Map<String, String> inputMapping,
                               Map<String, String> outputMapping) {
        this.nodeId = nodeId;
        this.workflowId = workflowId;
        this.inputMapping = inputMapping != null ? Map.copyOf(inputMapping) : Map.of();
        this.outputMapping = outputMapping != null ? Map.copyOf(outputMapping) : Map.of();
    }

    @Override
    public String id() { return nodeId; }
    public String nodeId() { return nodeId; }
    public String workflowId() { return workflowId; }
    public Map<String, String> inputMapping() { return inputMapping; }
    public Map<String, String> outputMapping() { return outputMapping; }

    /** Factory method: creates a new NestedWorkflowNode.Builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for NestedWorkflowNode.
     */
    public static class Builder {
        private String nodeId;
        private String workflowId;
        private Map<String, String> inputMapping;
        private Map<String, String> outputMapping;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder workflowId(String workflowId) { this.workflowId = workflowId; return this; }
        public Builder inputMapping(Map<String, String> inputMapping) {
            this.inputMapping = Map.copyOf(inputMapping);
            return this;
        }
        public Builder outputMapping(Map<String, String> outputMapping) {
            this.outputMapping = Map.copyOf(outputMapping);
            return this;
        }

        public NestedWorkflowNode build() {
            if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId required");
            if (workflowId == null || workflowId.isBlank()) throw new IllegalArgumentException("workflowId required");
            return new NestedWorkflowNode(nodeId, workflowId, inputMapping, outputMapping);
        }
    }
}
