package de.augmentia.quad.workflow.engine.port;

import de.augmentia.quad.core.agent.Agent;

import java.util.Map;
import de.augmentia.quad.workflow.engine.port.AgentInvocationPort;

/**
 * Port fuer den Aufruf eines Quad-Agenten aus einem Agent-Knoten heraus.
 *
 * <p>{@code AgentNodeExecutor} war an zwei Quarkus-Beans gebunden:
 * {@code ChannelAgentFactory} (baut den Agent) und {@code MessagingRouter}
 * (versendet die Nachricht). Damit konnte die Engine keinen Agent-Knoten
 * ausfuehren - und der Spring-Boot-Worker schon gar nicht, obwohl er genau das
 * als LLM-Worker tun soll.
 *
 * <p>Implementierung: {@code AgentInvocationAdapter} in quad-quarkus.
 */
public interface AgentInvocationPort {

    /**
     * Baut den Agenten fuer einen Agent-Knoten. Die Konfiguration des Knotens
     * (systemPrompt, userMessageTemplate, jsonOutput, cachePrefix) wird danach
     * vom Executor direkt am {@link Agent} gesetzt - das bleibt bewusst hier,
     * weil es reine {@code quad-core}-Logik ist und keinen Container braucht.
     *
     * @param agentId Agent-ID aus dem Workflow-Definition
     * @return fertig gebauter, noch nicht ausgefuehrter Agent
     */
    Agent create(String agentId);

    /**
     * Per-Agent-Timeout in Millisekunden, 0 wenn der Agent keinen eigenen
     * Timeout setzt. Teil der Timeout-Kette in {@code resolveTimeoutMs}:
     * knoten.timeoutMs → Agent-Timeout → globaler Default.
     */
    long timeoutMsOrZero(String agentId);

    /**
     * Versendet eine Nachricht an einen bestimmten Outbound-Channel, ohne einen
     * Agent zu starten. Wird von {@code AgentNodeExecutor} nach dem Lauf
     * benutzt, um das Ergebnis nach aussen zu melden.
     *
     * @param channelName Zielchannel
     * @param message     Nachricht; der Adapter serialisiert
     */
    void sendToOutbound(String channelName, String message);
}