package de.augmentia.quad.examples;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.config.ModelFactory;
import de.augmentia.quad.core.hook.plugin.Plugin;
import de.augmentia.quad.core.hook.plugin.PluginRegistry;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.ToolArgsMapper;

import java.util.List;

/**
 * Demonstrates the plugin system with custom hooks and tools.
 */
public class PluginDemoAgent extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Tool(description = "Finds files matching a pattern in the workspace")
    public String findFiles(@Param("pattern") String pattern) {
        return "Found files matching: " + pattern;
    }

    public static void main(String... args) {
        PluginDemoAgent agent = new PluginDemoAgent();
        agent.setLlm(ModelFactory.createOpenAiFromEnv());

        ToolArgsMapper argsMapper = new ToolArgsMapper(new com.fasterxml.jackson.databind.ObjectMapper());
        QuadToolRegistry registry = new QuadToolRegistry(argsMapper);
        agent.setToolRegistry(registry);

        Plugin demoPlugin = new Plugin() {
            @Override public String name() { return "demo-plugin"; }
            @Override public void initAgent(Agent a) { }
        };

        new PluginRegistry(List.of(demoPlugin)).initialize(agent);

        System.out.println("Plugin Demo Agent initialized");
    }
}