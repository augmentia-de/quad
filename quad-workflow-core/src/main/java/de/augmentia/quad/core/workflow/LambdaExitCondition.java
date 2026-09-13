package de.augmentia.quad.core.workflow;
import de.augmentia.quad.core.scope.AgentScope;

import java.util.Locale;
import java.util.function.Function;

/**
 * Exit condition backed by a Java lambda predicate.
 * <p>
 * Use the static factory methods for common patterns:
 *
 * <pre>{@code
 * // Check if a score key exceeds a threshold:
 * ExitCondition cond = LambdaExitCondition.scoreGte("planScore", 0.8);
 *
 * // Custom logic:
 * ExitCondition cond = new LambdaExitCondition(
 *     scope -> ((Double) scope.get("evalScore")) >= 0.9,
 *     "evalScore >= 0.9"
 * );
 * }</pre>
 */
public final class LambdaExitCondition implements ExitCondition {

    private final Function<AgentScope, Boolean> predicate;
    private final String description;

    public LambdaExitCondition(Function<AgentScope, Boolean> predicate, String description) {
        this.predicate = predicate;
        this.description = description;
    }

    @Override
    public boolean shouldExit(AgentScope scope) {
        return predicate.apply(scope);
    }

    @Override
    public String describe() {
        return description;
    }

    /**
     * Creates an exit condition that triggers when {@code scoreKey} >= threshold.
     */
    public static ExitCondition scoreGte(String scoreKey, double threshold) {
        return new LambdaExitCondition(
            scope -> {
                Object value = scope.get(scoreKey);
                if (value == null) return false;
                double score = extractDouble(value);
                return score >= threshold;
            },
            String.format(Locale.US, "%s >= %.1f", scoreKey, threshold)
        );
    }

    /**
     * Creates an exit condition that triggers when {@code scoreKey} <= threshold.
     */
    public static ExitCondition scoreLte(String scoreKey, double threshold) {
        return new LambdaExitCondition(
            scope -> {
                Object value = scope.get(scoreKey);
                if (value == null) return false;
                double score = extractDouble(value);
                return score <= threshold;
            },
            String.format(Locale.US, "%s <= %.1f", scoreKey, threshold)
        );
    }

    /**
     * Creates an exit condition that triggers when {@code scoreKey} > threshold.
     */
    public static ExitCondition scoreGt(String scoreKey, double threshold) {
        return new LambdaExitCondition(
            scope -> {
                Object value = scope.get(scoreKey);
                if (value == null) return false;
                double score = extractDouble(value);
                return score > threshold;
            },
            String.format(Locale.US, "%s > %.1f", scoreKey, threshold)
        );
    }

    private static double extractDouble(Object value) {
        if (value instanceof Number num) {
            return num.doubleValue();
        }
        if (value instanceof String str) {
            return Double.parseDouble(str);
        }
        throw new IllegalStateException("Cannot extract Double from: " + value.getClass().getName());
    }
}
