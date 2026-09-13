package de.augmentia.quad.core.workflow;
import de.augmentia.quad.core.scope.AgentScope;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exit condition parsed from a declarative string expression.
 * <p>
 * Supports syntax: {@code scoreKey operator value} where operator is one of: {@code >=, <=, >, <, ==}.
 *
 * <pre>{@code
 * ExitCondition cond = ExpressionExitCondition.fromExpression("planScore >= 0.8");
 * }</pre>
 */
public final class ExpressionExitCondition implements ExitCondition {

    private static final Pattern EXPRESSION_PATTERN =
        Pattern.compile("\\s*([a-zA-Z_][a-zA-Z0-9_.]*)\\s*(>=|<=|>|<|==)\\s*([\\d.]+)\\s*");

    public enum Operator {
        GTE(">=") {
            @Override boolean test(double left, double right) { return left >= right; }
        },
        LTE("<=") {
            @Override boolean test(double left, double right) { return left <= right; }
        },
        GT(">") {
            @Override boolean test(double left, double right) { return left > right; }
        },
        LT("<") {
            @Override boolean test(double left, double right) { return left < right; }
        },
        EQ("==") {
            @Override boolean test(double left, double right) { return Math.abs(left - right) < 1e-9; }
        };

        private final String symbol;
        Operator(String symbol) { this.symbol = symbol; }
        abstract boolean test(double left, double right);
        String symbol() { return symbol; }

        static Operator parse(String symbol) {
            for (Operator op : values()) {
                if (op.symbol.equals(symbol)) return op;
            }
            throw new IllegalArgumentException("Unknown comparison operator: " + symbol
                + ". Supported: >=, <=, >, <, ==");
        }
    }

    private final String key;
    private final Operator operator;
    private final double threshold;
    private final String rawExpression;

    private ExpressionExitCondition(String key, Operator operator, double threshold, String rawExpression) {
        this.key = key;
        this.operator = operator;
        this.threshold = threshold;
        this.rawExpression = rawExpression;
    }

    @Override
    public boolean shouldExit(AgentScope scope) {
        Object value = scope.get(key);
        if (value == null) return false;
        double actual = extractDouble(value);
        return operator.test(actual, threshold);
    }

    @Override
    public String describe() {
        return rawExpression;
    }

    /**
     * Parses a string expression and returns an ExitCondition.
     *
     * @param expression format: "scoreKey operator value" (e.g., "planScore >= 0.8")
     * @throws IllegalArgumentException if the expression is malformed
     */
    public static ExitCondition fromExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Expression must not be null or blank");
        }

        Matcher matcher = EXPRESSION_PATTERN.matcher(expression);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                "Invalid exit condition expression: '" + expression + "'. Expected format: 'key operator value' "
                + "(operators: >=, <=, >, <, ==). Example: 'planScore >= 0.8'");
        }

        String key = matcher.group(1);
        String opSymbol = matcher.group(2);
        double threshold = Double.parseDouble(matcher.group(3));
        Operator op = Operator.parse(opSymbol);

        return new ExpressionExitCondition(key, op, threshold, expression.trim());
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
