package de.augmentia.quad.quarkus.workflow.adapter;

import de.augmentia.quad.quarkus.messaging.ChannelAgentFactory;
import de.augmentia.quad.quarkus.messaging.MessagingRouter;
import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.messaging.QuadMessage;
import de.augmentia.quad.workflow.engine.port.AgentInvocationPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Bindet die Engine an die Quarkus-Agenten- und Messaging-Schicht.
 *
 * <p>{@code AgentNodeExecutor} rief vorher direkt {@code ChannelAgentFactory}
 * und {@code MessagingRouter}. Beides sind Quarkus-Beans; die Engine haette
 * damit ein weiteres Framework-Binding gehabt.
 *
 * <p>Die Serialisierung der Ausgangsnachricht liegt hier: die Engine uebergibt
 * den bereits gerenderten Text, der Adapter baut daraus die
 * {@link QuadMessage}. So bleibt die Nachrichten-Darstellung in der App.
 */
@ApplicationScoped
public class AgentInvocationAdapter implements AgentInvocationPort {

    private final ChannelAgentFactory agentFactory;
    private final MessagingRouter messagingRouter;

    @Inject
    public AgentInvocationAdapter(ChannelAgentFactory agentFactory, MessagingRouter messagingRouter) {
        this.agentFactory = agentFactory;
        this.messagingRouter = messagingRouter;
    }

    @Override
    public Agent create(String agentId) {
        return agentFactory.create(agentId);
    }

    @Override
    public long timeoutMsOrZero(String agentId) {
        return agentFactory.timeoutMsOrZero(agentId);
    }

    @Override
    public void sendToOutbound(String channelName, String message) {
        messagingRouter.sendToSpecificOutbound(channelName,
                QuadMessage.of("workflow-out", "text", message != null ? message : ""));
    }
}