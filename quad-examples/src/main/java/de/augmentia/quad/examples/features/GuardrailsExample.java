package de.augmentia.quad.examples.features;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.guardrails.Guardrail;
import de.augmentia.quad.core.guardrails.GuardrailPlugin;
import de.augmentia.quad.core.guardrails.GuardrailResult;
import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * Feature 3: Guardrails (GuardrailPlugin)
 *
* A ContentModeratorAgent that checks inbound prompts for harmful
* instructions or insults.
 */
class GuardrailsExample extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Tool(description = "Verarbeitet Anfragen")
    public String handle(String input) {
        return "Behandelt: " + input;
    }

    public static void main(String[] args) {
        GuardrailsExample agent = AgentBuilder.create(GuardrailsExample.class)
            .withLlmFromEnv()

            .build();

        Guardrail contentGuard = (messages, context) -> {
            String text = messages.stream()
                .map(ChatMessage::toString)
                .reduce("", String::concat)
                .toLowerCase();
            if (text.contains("badword") || text.contains("verboten")) {
                return GuardrailResult.block("Unwanted content detected");
            }
            return GuardrailResult.ok();
        };

        agent.addHook(new GuardrailPlugin(List.of(contentGuard), List.of()));

        String result = agent.executeReAct("Bitte verarbeite diese Anfrage");
        System.out.println(result);
    }
}
