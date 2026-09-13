package de.augmentia.quad.core.workflow;
import de.augmentia.quad.core.scope.AgentScope;

import de.augmentia.quad.core.agent.AgentResult;

/**
 * Result of an executed agent workflow: the shared scope plus the result of
 * the last agent step.
 */
public record AgentWorkflowResult(
    AgentScope scope,
    AgentResult lastResult
) {

    public String workflowId() {
        return scope.workflowId();
    }

    /** Convenience accessor for the text of the final step. */
    public String finalText() {
        return lastResult != null ? lastResult.finalAnswer() : null;
    }

    /** Convenience accessor for the structured output of the final step. */
    public String finalStructuredOutput() {
        return lastResult != null ? lastResult.structuredOutput() : null;
    }

    /** Typed accessor for a value stored in the workflow scope. */
    public <T> T scopeValue(String key) {
        return scope.get(key);
    }
}