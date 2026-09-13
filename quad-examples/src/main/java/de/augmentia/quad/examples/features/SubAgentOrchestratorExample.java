package de.augmentia.quad.examples.features;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.session.AgentSessionState;

/**
 * Feature 1: ReAct-Flow &amp; Sub-Agenten (SubAgentTool)
 *
 * Ein OrchestratorAgent delegiert Teilaufgaben an einen spezialisierten
 * Sub-agents via the ReAct loop (iterative Reason -> Act -> Observation).
 */
public class SubAgentOrchestratorExample extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Tool(description = "Fasst Ergebnisse zusammen")
    public String summarize(@Param("findings") String findings) {
        return "Zusammenfassung: " + findings;
    }

    public static void main(String[] args) {
        Agent subAgent = AgentBuilder.create(ResearchSubAgent.class)
            .withLlmFromEnv()
            .build();

        SubAgentOrchestratorExample orchestrator = AgentBuilder.create(SubAgentOrchestratorExample.class)
            .withLlmFromEnv()
            .withSubAgent("researcher", subAgent)
            .build();

        String result = orchestrator.executeReAct(
            "Recherchiere Trends zu Cloud-Native und fasse zusammen."
        );
        System.out.println(result);
    }
}

class ResearchSubAgent extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Tool(description = "Sucht nach relevanten Dokumentationen")
    public String search(@Param("query") String query) {
        return "Found results for: " + query;
    }

    @Tool(description = "Extracts key information")
    public String extract(@Param("source") String source) {
        return "Extrahierte Infos: " + source;
    }
}
