package de.augmentia.quad.core.hook.plugin;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.guardrails.BlockAction;
import de.augmentia.quad.core.guardrails.Guardrail;
import de.augmentia.quad.core.hook.pipeline.AgentHook;
import de.augmentia.quad.core.tool.ToolMethod;

import java.util.List;

/**
 * Extends {@link AgentHook} with agent lifecycle, tool, and guardrail support.
 */
public interface Plugin extends AgentHook {

    String name();

    default int order() { return 0; }

    default void initAgent(Agent agent) {}

    default void onDestroy() {}

    default List<ToolMethod> getTools() {
        return List.of();
    }

    default List<Guardrail> getInputGuardrails() {
        return List.of();
    }

    default List<Guardrail> getOutputGuardrails() {
        return List.of();
    }

    default BlockAction getBlockAction() {
        return BlockAction.THROW;
    }

    default String getFallbackMessage() {
        return "Request blocked";
    }
}
