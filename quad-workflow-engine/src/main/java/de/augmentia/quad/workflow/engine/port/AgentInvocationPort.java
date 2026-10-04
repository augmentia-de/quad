package de.augmentia.quad.workflow.engine.port;

import de.augmentia.quad.core.agent.Agent;

import java.util.Map;
import de.augmentia.quad.workflow.engine.port.AgentInvocationPort;

/**
 * Port for invoking a quad agent from an agent node.
 *
 * <p>{@code AgentNodeExecutor} was bound to two Quarkus beans:
 * {@code ChannelAgentFactory} (builds the agent) and {@code MessagingRouter}
 * (sends the message). That way the engine could not
 * be executed — and the Spring Boot worker could not at all, although it is
 * supposed to do exactly that as an LLM worker.
 *
 * <p>Implementierung: {@code AgentInvocationAdapter} in quad-quarkus.
 */
public interface AgentInvocationPort {

    /**
     * Builds the agent for an agent node. The configuration of the node
     * (systemPrompt, userMessageTemplate, jsonOutput, cachePrefix) is then
     * set by the executor directly on the {@link Agent} — that deliberately stays here,
     * because it is pure {@code quad-core} logic and needs no container.
     *
     * @param agentId agent ID from the workflow definition
     * @return the fully built agent, not yet executed
     */
    Agent create(String agentId);

    /**
     * Per-agent timeout in milliseconds, 0 if the agent sets no
     * timeout of its own. Part of the timeout chain in {@code resolveTimeoutMs}:
     * node.timeoutMs → agent timeout → global default.
     */
    long timeoutMsOrZero(String agentId);

    /**
     * Sends a message to a given outbound channel without starting
     * an agent. Used by {@code AgentNodeExecutor} after the run
     * to report the result outwards.
     *
     * @param channelName target channel
     * @param message     message; the adapter serialises
     */
    void sendToOutbound(String channelName, String message);
}