package de.augmentia.quad.quarkus.messaging.kafka;

import de.augmentia.quad.core.agent.messaging.QuadMessage;
import de.augmentia.quad.core.agent.messaging.OutboundChannel;
import io.quarkus.arc.properties.IfBuildProperty;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

@ApplicationScoped
@IfBuildProperty(name = "quad.messaging.kafka.outbound.enabled", stringValue = "true")
public class KafkaOutboundChannel implements OutboundChannel {

    private static final Logger log = Logger.getLogger(KafkaOutboundChannel.class);

    @Channel("quad-out-kafka")
    Emitter<String> emitter;

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public void send(QuadMessage message) {
        emitter.send(message.payload())
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.errorf("Failed to send to Kafka: %s", ex.getMessage());
                } else {
                    log.debugf("Sent to Kafka: %s", message.payload().length() > 50
                        ? message.payload().substring(0, 50) + "..." : message.payload());
                }
            });
    }

    @Override
    public boolean isAvailable() {
        return emitter != null;
    }
}
