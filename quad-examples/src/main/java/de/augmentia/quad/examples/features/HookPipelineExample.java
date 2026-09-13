package de.augmentia.quad.examples.features;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.hook.pipeline.AgentHook;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;
import de.augmentia.quad.core.session.AgentSessionState;

/**
 * Feature 2: Hook-Pipeline (AgentHook / HookRegistry)
 *
 * Ein SecurityFilterAgent, der das Anfragen geheimer Begriffe
 * vor dem Modellaufruf unterbindet.
 */
public class HookPipelineExample extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Tool(description = "Verarbeitet Eingabedaten")
    public String process(String data) {
        return "Verarbeitet: " + data;
    }

    public static void main(String[] args) {
        HookPipelineExample agent = AgentBuilder.create(HookPipelineExample.class)
            .withLlmFromEnv()
            .build();

        agent.addHook(new AgentHook() {
            @Override
            public String name() {
                return "SecretBlocker";
            }

            @Override
            public HookResult beforeModelCall(HookContexts.BeforeModelCallContext ctx) {
                if (ctx.messages().toString().contains("geheim")) {
                    return new HookResult.Cancel("Sicherheitsrichtlinie verletzt!");
                }
                return HookResult.Continue.INSTANCE;
            }
        });

        String result = agent.executeReAct("Verarbeite sichere Daten");
        System.out.println(result);
    }
}
