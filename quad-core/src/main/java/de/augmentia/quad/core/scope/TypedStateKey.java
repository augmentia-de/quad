package de.augmentia.quad.core.scope;

/**
 * Statically typed key for workflow scope values.
 * <p>
 * Provides type-safe access to shared state between workflow steps,
 * equivalent to langchain4j-agentic's {@code @K(Key.class)} pattern.
 *
 * <pre>{@code
 * static final TypedStateKey<String> USER_REQUEST = new TypedStateKey<>("userRequest", String.class);
 * static final TypedStateKey<Double> PLAN_SCORE = new TypedStateKey<>("planScore", Double.class);
 * }</pre>
 */
public record TypedStateKey<T>(String name, Class<T> type) {

    /**
     * Convenience factory for String keys.
     */
    public static TypedStateKey<String> string(String name) {
        return new TypedStateKey<>(name, String.class);
    }

    /**
     * Convenience factory for Double keys.
     */
    public static TypedStateKey<Double> numeric(String name) {
        return new TypedStateKey<>(name, Double.class);
    }

    /**
     * Convenience factory for Integer keys.
     */
    public static TypedStateKey<Integer> integer(String name) {
        return new TypedStateKey<>(name, Integer.class);
    }
}
