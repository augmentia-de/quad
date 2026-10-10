package de.augmentia.quad.examples.quickstart;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.agent.AgentConfig;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;

/**
 * Schritt 3 von 5 — Konfigurierter Agent, Tools ohne Subclassing.
 *
 * <p>Zeigt: die kanonische {@link AgentConfig} als einzige Konfigurationsquelle
 * (System-Prompt, Sampling, Limits) und Tools, die als einfache Objekte übergeben werden.
 * Dafür ist keine eigene Agent-Klasse nötig.</p>
 *
 * <p><b>Neu gegenüber Schritt 2:</b> Konfiguration über ein Wertobjekt statt einzelner
 * Setter; Tools via {@code withToolObjects(...)}. Dieselbe Config lässt sich auch aus
 * JSON laden — {@code AgentConfig.fromJson(...)} — und ist damit der gemeinsame Weg für
 * programmatische und dateibasierte Konfiguration.</p>
 */
public final class Step3_ConfiguredAgent {

    private Step3_ConfiguredAgent() {
    }

    /** Tool-Objekt: keine Agent-Subklasse, nur {@code @Tool}-Methoden. */
    public static class TimeTools {

        @Tool(description = "Gibt die aktuelle Uhrzeit für eine Zeitzone zurück")
        public String currentTime(@Param("zone") String zone) {
            return "Uhrzeit in " + zone + ": " + java.time.LocalTime.now();
        }
    }

    public static void main(String[] args) {
        AgentConfig config = AgentConfig.builder()
                .modelFromEnv()
                .systemPrompt("Du bist ein knapper Assistent. Antworte in einem Satz.")
                .temperature(0.2)
                .maxOutputTokens(256)
                .maxReActIterations(5)
                .contextMaxTokens(4_000)
                .build();

        Agent agent = AgentBuilder.defaultAgent()
                .withConfig(config)
                .withToolObjects(new TimeTools())
                .build();

        System.out.println(agent.run("Wie spät ist es in Europe/Berlin? Nutze das Tool."));
    }
}
