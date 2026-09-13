package de.augmentia.quad.examples.features;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.guardrails.Guardrail;
import de.augmentia.quad.core.guardrails.GuardrailResult;
import de.augmentia.quad.core.hook.pipeline.AgentHook;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;
import de.augmentia.quad.core.hook.plugin.Plugin;
import de.augmentia.quad.core.hook.plugin.PluginRegistry;
import de.augmentia.quad.core.session.AgentSessionState;

import java.util.List;

/**
 * Feature 4: Plugins (Plugin / PluginRegistry)
 *
 * A ModularAgent equipped with extension hooks
 * and guardrails via a plugin.
 */
class PluginExample extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Tool(description = "Basic tool for the agent")
    public String handle(String input) {
        return "Erledigt: " + input;
    }

    public static class CustomMonitoringPlugin implements Plugin {

        @Override
        public String name() {
            return "CustomMonitoringPlugin";
        }

        @Override
        public void initAgent(Agent agent) {
            agent.addHook(new AgentHook() {
                @Override
                public String name() {
                    return "MetricCollector";
                }

                @Override
                public HookResult afterAgent(HookContexts.AfterAgentContext ctx, String response) {
                    System.out.println("[METRIC] Agent completed: result=" + response);
                    return HookResult.Continue.INSTANCE;
                }
            });
        }

        @Override
        public List<Guardrail> getInputGuardrails() {
            return List.of((messages, context) -> {
                String combined = messages.toString().toLowerCase();
                return combined.contains("block")
                    ? GuardrailResult.block("Blockwort erkannt")
                    : GuardrailResult.ok();
            });
        }
    }

    public static void main(String[] args) {
        PluginExample agent = AgentBuilder.create(PluginExample.class)
            .withLlmFromEnv()
            .build();

        new PluginRegistry(List.of(new CustomMonitoringPlugin())).initialize(agent);

        String result = agent.executeReAct("Normale Anfrage");
        System.out.println(result);
    }
}
