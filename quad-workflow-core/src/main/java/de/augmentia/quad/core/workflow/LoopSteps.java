package de.augmentia.quad.core.workflow;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.config.StructuredOutputConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Represents the sequence of steps executed within a loop iteration.
 */
public final class LoopSteps {

    public record AgentStep(Agent agent, StructuredOutputConfig outputConfig, String outputKey) {}
    public record TransformStep(Function<Object, Object> transformer,
                                String fromKey, String toKey) {}

    private final List<Object> steps = new ArrayList<>();

    /** Adds an agent step. */
    public void add(Agent agent, StructuredOutputConfig outputConfig, String outputKey) {
        steps.add(new AgentStep(agent, outputConfig, outputKey));
    }

    /** Adds a transform step (no LLM call). */
    public void addTransformer(Function<Object, Object> transformer,
                               String fromKey, String toKey) {
        steps.add(new TransformStep(transformer, fromKey, toKey));
    }

    @SuppressWarnings("unchecked")
    <T extends LoopSteps.AgentStep> T agentStep(int index) {
        return (T) steps.get(index);
    }

    @SuppressWarnings("unchecked")
    <T extends LoopSteps.TransformStep> T transformStep(int index) {
        return (T) steps.get(index);
    }

    public int size() { return steps.size(); }

    /** Returns the list of steps (AgentStep or TransformStep objects). */
    public List<Object> steps() { return List.copyOf(steps); }
}
