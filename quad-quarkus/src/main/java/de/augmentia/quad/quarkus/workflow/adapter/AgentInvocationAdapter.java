package de.augmentia.quad.quarkus.workflow.adapter;

import de.augmentia.quad.quarkus.messaging.ChannelAgentFactory;
import de.augmentia.quad.quarkus.messaging.MessagingRouter;
import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.messaging.QuadMessage;
import de.augmentia.quad.workflow.engine.port.AgentInvocationPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Binds the engine to the Quarkus agent and messaging layer.
 *
 * <p>{@code AgentNodeExecutor} previously called {@code ChannelAgentFactory}
 * and {@code MessagingRouter} directly. Both are Quarkus beans; the engine would
 * have had yet another framework binding.
 *
 * <p>The serialisation of the outgoing message lives here: the engine hands over
 * the already rendered text, the adapter builds the
 * {@link QuadMessage} from it. That way the message representation stays in the app.
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