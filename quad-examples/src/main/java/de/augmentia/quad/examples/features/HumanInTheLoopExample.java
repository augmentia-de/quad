package de.augmentia.quad.examples.features;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.hitl.HITLPlugin;
import de.augmentia.quad.core.hitl.checkpoint.CheckpointService;
import de.augmentia.quad.core.hitl.checkpoint.ConsoleChannel;
import de.augmentia.quad.core.hitl.checkpoint.InMemoryCheckpointStore;
import de.augmentia.quad.core.session.AgentSessionState;

/**
 * Feature 7: Human-in-the-Loop (HITL) &amp; Checkpoints
 *
* An ApprovalAgent that requires confirmation from the console before executing
* critical system commands.
 */
public class HumanInTheLoopExample extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Tool(description = "Critical system command requiring HITL approval")
    public String executeBash(@Param("script") String script) {
        return "Command executed: " + script;
    }

    @Tool(description = "Unkritische Informationsabfrage")
    public String getInfo(@Param("topic") String topic) {
        return "Info zu " + topic;
    }

    public static void main(String[] args) {
        HumanInTheLoopExample agent = AgentBuilder.create(HumanInTheLoopExample.class)
            .withLlmFromEnv()
            .build();

        CheckpointService checkpointService = new CheckpointService(
            new InMemoryCheckpointStore(), "executeBash", 120_000
        );
        checkpointService.registerChannel(new ConsoleChannel());
        agent.addHook(new HITLPlugin(checkpointService));

        String result = agent.executeReAct(
            "Run executeBash with 'ls -la' and then get info about the system status."
        );
        System.out.println(result);
    }
}
