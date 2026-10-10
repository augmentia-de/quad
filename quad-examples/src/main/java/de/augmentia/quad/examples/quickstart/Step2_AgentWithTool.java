package de.augmentia.quad.examples.quickstart;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.session.AgentSessionState;

/**
 * Schritt 2 von 5 — Eigener Agent mit einem Tool.
 *
 * <p>Zeigt: eine eigene Agent-Klasse mit einer {@code @Tool}-Methode. Der ReAct-Loop des
 * Agenten kann das Tool selbstständig aufrufen. {@code @Param} benennt die Argumente im
 * JSON-Schema.</p>
 *
 * <p><b>Neu gegenüber Schritt 1:</b> Vererbung von {@link Agent} und ein eigenes Tool.
 * Gebaut wird über {@link AgentBuilder}, der das Tool automatisch registriert.</p>
 */
public final class Step2_AgentWithTool {

    private Step2_AgentWithTool() {
    }

    /** Eigener Agent: eine Klasse, ein Tool. */
    public static class CalculatorAgent extends Agent {

        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }

        @Tool(description = "Addiert zwei ganze Zahlen")
        public String add(@Param("a") int a, @Param("b") int b) {
            return String.valueOf(a + b);
        }
    }

    public static void main(String[] args) {
        Agent agent = AgentBuilder.create(CalculatorAgent.class)
                .withLlmFromEnv()
                .withSystemPrompt("Du bist ein Rechen-Assistent. Nutze das Tool 'add'.")
                .build();

        System.out.println(agent.run("Was ergibt 21 + 21? Nutze das Tool add."));
    }
}
