package de.augmentia.quad.core.agent;

/**
 * Convenience facade for the common agent entry points.
 *
 * <p>Keeps the "simple entry" discoverable without hiding the configurable path:
 * {@link #simple()} for a ready-to-use agent, {@link #builder(Class)} for full control,
 * {@link #of(Object...)} for tools without subclassing, {@link #fromConfig(AgentConfig)}
 * for config-driven construction.</p>
 */
public final class Agents {

    private Agents() {
    }

    /** Ready-to-use default agent: model from environment, logging, tool registration. */
    public static QuadAgent simple() {
        return QuadAgent.create();
    }

    /** A builder for a concrete agent class. */
    public static <T extends Agent> AgentBuilder<T> builder(Class<T> agentClass) {
        return AgentBuilder.create(agentClass);
    }

    /**
     * Default agent whose tools are supplied as plain objects with {@code @Tool} methods,
     * using the model from the environment.
     */
    public static Agent of(Object... toolObjects) {
        return AgentBuilder.defaultAgent()
                .withLlmFromEnv()
                .withToolObjects(toolObjects)
                .build();
    }

    /** Default agent built from a canonical {@link AgentConfig}. */
    public static Agent fromConfig(AgentConfig config) {
        return AgentBuilder.defaultAgent()
                .withConfig(config)
                .build();
    }
}
