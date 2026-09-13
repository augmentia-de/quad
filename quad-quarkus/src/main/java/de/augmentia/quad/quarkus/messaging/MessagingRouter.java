package de.augmentia.quad.quarkus.messaging;

import de.augmentia.quad.core.agent.messaging.InboundChannel;
import de.augmentia.quad.core.agent.messaging.QuadMessage;
import de.augmentia.quad.core.agent.messaging.OutboundChannel;
import de.augmentia.quad.quarkus.workflow.engine.WorkflowEngine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class MessagingRouter {

    private static final Logger log = Logger.getLogger(MessagingRouter.class);

    @Inject
    @Any
    List<InboundChannel> inboundChannels;

    @Inject
    @Any
    List<OutboundChannel> outboundChannels;

    @Inject
    MessagingProcessor processor;

    @Inject
    WorkflowEngine workflowEngine;

    private final Map<String, InboundChannel> runningChannels = new ConcurrentHashMap<>();

    @PostConstruct
    void start() {
        String enabled = System.getProperty("quad.messaging.enabled",
            System.getenv().getOrDefault("QUAD_MESSAGING_ENABLED", "false"));

        if (!"true".equalsIgnoreCase(enabled)) {
            log.info("Messaging disabled (quad.messaging.enabled=false)");
            return;
        }

        for (InboundChannel channel : inboundOrEmpty()) {
            if (isChannelEnabled(channel.name())) {
                log.infof("Starting inbound channel: %s", channel.name());
                channel.start(this::handleMessage);
                runningChannels.put(channel.name(), channel);
            }
        }

        if (runningChannels.isEmpty()) {
            log.info("No inbound channels enabled");
        } else {
            log.infof("Active inbound channels: %s", runningChannels.keySet());
        }
    }

    @PreDestroy
    void stop() {
        for (InboundChannel channel : runningChannels.values()) {
            log.infof("Stopping inbound channel: %s", channel.name());
            channel.stop();
        }
        runningChannels.clear();
    }

    public void handleMessage(QuadMessage message) {
        log.infof("Received message from %s: %s (session=%s)",
            message.source(), message.contentType(), message.sessionId());

        try {
            String topic = message.headers().get("topic");
            String tenantId = message.headers().getOrDefault("tenantId", "default");
            if (topic != null && workflowEngine.onMessage(tenantId, topic, message.payload())) {
                log.infof("Message forwarded to workflow engine (tenant=%s, topic=%s)", tenantId, topic);
                return;
            }

            String sessionId = message.sessionId() != null
                ? message.sessionId()
                : "messaging-" + message.source();

            String agentId = message.headers().get("agentId");

            String result = processor.process(sessionId, message.payload(), agentId);

            QuadMessage response = message
                .withSession(sessionId)
                .withPayload(result);

            String outboundName = resolveOutboundChannel(agentId);
            if (outboundName != null) {
                sendToSpecificOutbound(outboundName, response);
            } else {
                sendToOutbound(response);
            }

        } catch (Exception e) {
            log.errorf("Failed to process message from %s: %s", message.source(), e.getMessage());
        }
    }

    private String resolveOutboundChannel(String agentId) {
        if (agentId == null) return null;
        String envKey = "QUAD_MESSAGING_AGENT_" +
            agentId.toUpperCase().replace('-', '_') + "_OUTBOUND_CHANNEL";
        return System.getProperty(
            "quad.messaging.agent." + agentId + ".outbound-channel",
            System.getenv().getOrDefault(envKey, null));
    }

    public void sendToSpecificOutbound(String channelName, QuadMessage message) {
        for (OutboundChannel ch : outboundOrEmpty()) {
            if (ch.name().equals(channelName) && ch.isAvailable()) {
                try {
                    ch.send(message);
                    log.debugf("Sent to outbound channel: %s", channelName);
                } catch (Exception e) {
                    log.errorf("Failed to send to outbound channel %s: %s", channelName, e.getMessage());
                }
                return;
            }
        }
        log.warnf("Outbound channel '%s' not found or unavailable, falling back to fan-out", channelName);
        sendToOutbound(message);
    }

    public void sendToOutbound(QuadMessage message) {
        for (OutboundChannel channel : outboundOrEmpty()) {
            if (channel.isAvailable()) {
                try {
                    channel.send(message);
                    log.debugf("Sent result to outbound channel: %s", channel.name());
                } catch (Exception e) {
                    log.errorf("Failed to send to outbound channel %s: %s",
                        channel.name(), e.getMessage());
                }
            }
        }
    }

    private boolean isChannelEnabled(String channelName) {
        return switch (channelName) {
            case "kafka" -> "true".equalsIgnoreCase(
                System.getProperty("quad.messaging.kafka.inbound.enabled",
                    System.getenv().getOrDefault("QUAD_MESSAGING_KAFKA_INBOUND_ENABLED", "false")));
            case "amqp" -> "true".equalsIgnoreCase(
                System.getProperty("quad.messaging.amqp.inbound.enabled",
                    System.getenv().getOrDefault("QUAD_MESSAGING_AMQP_INBOUND_ENABLED", "false")));
            case "email" -> "true".equalsIgnoreCase(
                System.getProperty("quad.messaging.email.inbound.enabled",
                    System.getenv().getOrDefault("QUAD_MESSAGING_EMAIL_INBOUND_ENABLED", "false")));
            default -> false;
        };
    }

    public Map<String, InboundChannel> getRunningChannels() {
        return Map.copyOf(runningChannels);
    }

    public List<OutboundChannel> getOutboundChannels() {
        return List.copyOf(outboundOrEmpty());
    }

    private List<InboundChannel> inboundOrEmpty() {
        return inboundChannels == null ? List.of() : inboundChannels;
    }

    private List<OutboundChannel> outboundOrEmpty() {
        return outboundChannels == null ? List.of() : outboundChannels;
    }
}
