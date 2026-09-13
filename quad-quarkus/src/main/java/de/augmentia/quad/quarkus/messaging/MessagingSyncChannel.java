package de.augmentia.quad.quarkus.messaging;

import de.augmentia.quad.core.agent.messaging.SyncChannel;
import de.augmentia.quad.core.agent.messaging.QuadMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MessagingSyncChannel implements SyncChannel {

    private static final Logger log = Logger.getLogger(MessagingSyncChannel.class);

    @Inject
    ChannelAgentFactory agentFactory;

    @Override
    public String name() {
        return "sync";
    }

    @Override
    public String process(QuadMessage message) {
        String agentId = message.headers().get("agentId");
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId required for sync processing");
        }
        log.infof("Sync processing [agentId=%s]", agentId);
        return agentFactory.process(agentId, message.payload());
    }
}
