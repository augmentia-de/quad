package de.augmentia.quad.core.workflow;
import de.augmentia.quad.core.scope.AgentScope;

/**
 * Condition that determines whether a loop should exit.
 * <p>
 * Supports both programmatic (lambda) and declarative (string expression) definitions.
 *
 * <pre>{@code
 * // Lambda-based:
 * ExitCondition cond = LambdaExitCondition.scoreGte("planScore", 0.8);
 *
 * // Expression-based:
 * ExitCondition cond = ExpressionExitCondition.fromExpression("planScore >= 0.8");
 * }</pre>
 */
public interface ExitCondition {

    /**
     * Evaluates whether the loop should exit.
     *
     * @param scope current workflow scope with computed values
     * @return true if the loop should exit, false to continue
     */
    boolean shouldExit(AgentScope scope);

    /**
     * Human-readable description of this condition.
     */
    String describe();
}
