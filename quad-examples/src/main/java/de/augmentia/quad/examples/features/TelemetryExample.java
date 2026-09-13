package de.augmentia.quad.examples.features;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.agent.runtime.AgentEventPublisher;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.observability.LoggingHook;
import de.augmentia.quad.core.session.AgentSessionState;

/**
 * Feature 5: Telemetry &amp; Observability (AgentEventPublisher / LoggingHook)
 *
 * Ein MonitoredAgent, der Lifecycle-Events loggt und an die Konsole weiterleitet.
 */
class TelemetryExample extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Tool(description = "Analysiert Daten")
    public String analyze(String data) {
        return "Analyse erledigt: " + data;
    }

    public static void main(String[] args) {
        AgentEventPublisher eventPublisher = new AgentEventPublisher();
        eventPublisher.addEventListener(new LoggingHook()::onEvent);

        TelemetryExample agent = AgentBuilder.create(TelemetryExample.class)
            .withLlmFromEnv()
            .build();
        agent.setEventPublisher(eventPublisher);

        String result = agent.executeReAct("Analysiere die folgenden Metriken");
        System.out.println(result);

        System.out.println("\n=== Events geloggt ===");
        eventPublisher.events().forEach(e ->
            System.out.println("  " + e.role() + ": " + e.content()));
    }
}
