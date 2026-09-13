package de.augmentia.quad.core.hook.plugin;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.guardrails.GuardrailPlugin;

import java.util.List;

/**
 * Registry that initializes and registers all plugins with an agent.
 * Plugins contribute hooks, tools, and guardrails to the agent pipeline.
 */
public class PluginRegistry {

    private final List<Plugin> plugins;

    /**
     * @param plugins the plugins to manage; the list is defensively copied
     */
    public PluginRegistry(List<Plugin> plugins) {
        this.plugins = List.copyOf(plugins);
    }

    /**
     * Initialises each plugin with the given agent and registers its hooks, tools, and guardrails.
     */
    public void initialize(Agent agent) {
        var hookRegistry = agent.getHookRegistry();
        for (var plugin : plugins) {
            plugin.initAgent(agent);
            for (var toolMethod : plugin.getTools()) {
                agent.getToolRegistry().register(toolMethod.spec().name(), toolMethod);
            }
            if (!plugin.getInputGuardrails().isEmpty() || !plugin.getOutputGuardrails().isEmpty()) {
                hookRegistry.register(new GuardrailPlugin(
                    plugin.getInputGuardrails(), plugin.getOutputGuardrails(),
                    plugin.getBlockAction(), plugin.getFallbackMessage()));
            }
            hookRegistry.register(plugin);
        }
    }

    public List<Plugin> getPlugins() {
        return plugins;
    }
}
