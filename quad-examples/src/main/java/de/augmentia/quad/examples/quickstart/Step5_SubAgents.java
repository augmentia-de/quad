package de.augmentia.quad.examples.quickstart;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.session.AgentSessionState;

/**
 * Schritt 5 von 5 — Komposition über Sub-Agenten.
 *
 * <p>Zeigt: ein Orchestrator-Agent delegiert eine Teilaufgabe an einen spezialisierten
 * Sub-Agenten. Über {@code withSubAgent(name, agent)} erscheint der Sub-Agent als Tool und
 * läuft in seinem eigenen ReAct-Loop, während beide denselben Session-State teilen.</p>
 *
 * <p><b>Neu gegenüber Schritt 4:</b> aus mehreren Agenten wird ein System. Der Bau bleibt
 * derselbe ({@link AgentBuilder}); nur die Verdrahtung kommt hinzu.</p>
 */
public final class Step5_SubAgents {

    private Step5_SubAgents() {
    }

    /** Spezialisierter Sub-Agent mit eigenem Tool. */
    public static class ResearchAgent extends Agent {

        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }

        @Tool(description = "Sucht Stichpunkte zu einem Thema")
        public String search(@Param("topic") String topic) {
            return "Stichpunkte zu '" + topic + "': Cloud-Native, Kubernetes, Serverless.";
        }
    }

    /** Orchestrator, der den Sub-Agenten als Tool nutzt. */
    public static class Orchestrator extends Agent {

        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }

        @Tool(description = "Fasst Recherche-Ergebnisse zusammen")
        public String summarize(@Param("findings") String findings) {
            return "Zusammenfassung: " + findings;
        }
    }

    public static void main(String[] args) {
        Agent researcher = AgentBuilder.create(ResearchAgent.class)
                .withLlmFromEnv()
                .build();

        Agent orchestrator = AgentBuilder.create(Orchestrator.class)
                .withLlmFromEnv()
                .withSystemPrompt("Du koordinierst Recherche. Nutze 'researcher' und 'summarize'.")
                .withSubAgent("researcher", researcher)
                .build();

        System.out.println(orchestrator.run(
                "Recherchiere Trends zu Cloud-Native und fasse sie zusammen."));
    }
}
