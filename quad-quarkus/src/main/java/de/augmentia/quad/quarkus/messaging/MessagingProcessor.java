package de.augmentia.quad.quarkus.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MessagingProcessor {

    private static final Logger log = Logger.getLogger(MessagingProcessor.class);

    @Inject
    ChannelAgentFactory agentFactory;

    public String process(String sessionId, String payload, String agentId) {
        log.infof("Processing message [sessionId=%s, agentId=%s]", sessionId, agentId);
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId required for message processing");
        }
        return agentFactory.process(agentId, payload);
    }
}
