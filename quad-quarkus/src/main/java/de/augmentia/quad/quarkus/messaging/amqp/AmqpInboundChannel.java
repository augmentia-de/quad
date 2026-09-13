package de.augmentia.quad.quarkus.messaging.amqp;

import de.augmentia.quad.core.agent.messaging.InboundChannel;
import de.augmentia.quad.core.agent.messaging.QuadMessage;
import de.augmentia.quad.quarkus.messaging.MessagingRouter;
import de.augmentia.quad.quarkus.messaging.TopicAgentMapping;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
@IfBuildProperty(name = "quad.messaging.amqp.inbound.enabled", stringValue = "true")
public class AmqpInboundChannel implements InboundChannel {

    private static final Logger log = Logger.getLogger(AmqpInboundChannel.class);

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Inject
    TopicAgentMapping mapping;

    @Inject
    MessagingRouter router;

    @Override
    public String name() {
        return "amqp";
    }

    @Override
    public void start(MessageHandler handler) {
        this.running.set(true);
        log.info("AMQP inbound channel started (consumer not yet implemented)");
    }

    @Override
    public void stop() {
        this.running.set(false);
        log.info("AMQP inbound channel stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    public void onMessageReceived(String queueName, String payload) {
        if (!running.get()) {
            log.warn("AMQP message received but channel not running, discarding");
            return;
        }

        String tenantId = resolveTenant(queueName);
        String agentId = mapping.agentForTopic("amqp", tenantId, queueName);
        if (agentId == null) {
            log.warnf("No agent mapped for AMQP queue: %s (tenant=%s), discarding message", queueName, tenantId);
            return;
        }

        log.infof("AMQP message on queue %s (agentId=%s)", queueName, agentId);

        QuadMessage message = new QuadMessage(
            UUID.randomUUID().toString(),
            null,
            "amqp:" + queueName,
            "text",
            payload,
            Map.of("queue", queueName, "agentId", agentId, "tenantId", tenantId),
            java.time.Instant.now()
        );

        router.handleMessage(message);
    }

    private String resolveTenant(String queueName) {
        String envKey = "QUAD_MESSAGING_AMQP_QUEUE_" +
            queueName.toUpperCase().replace('-', '_') + "_TENANT";
        return System.getProperty(
            "quad.messaging.amqp.queue." + queueName + ".tenant",
            System.getenv().getOrDefault(envKey, "default"));
    }
}
