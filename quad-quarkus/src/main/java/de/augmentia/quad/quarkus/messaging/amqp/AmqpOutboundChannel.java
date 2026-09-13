package de.augmentia.quad.quarkus.messaging.amqp;

import de.augmentia.quad.core.agent.messaging.QuadMessage;
import de.augmentia.quad.core.agent.messaging.OutboundChannel;
import io.quarkus.arc.properties.IfBuildProperty;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

@ApplicationScoped
@IfBuildProperty(name = "quad.messaging.amqp.outbound.enabled", stringValue = "true")
public class AmqpOutboundChannel implements OutboundChannel {

    private static final Logger log = Logger.getLogger(AmqpOutboundChannel.class);

    @Channel("quad-out-amqp")
    Emitter<String> emitter;

    @Override
    public String name() {
        return "amqp";
    }

    @Override
    public void send(QuadMessage message) {
        emitter.send(message.payload())
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.errorf("Failed to send to AMQP: %s", ex.getMessage());
                } else {
                    log.debugf("Sent to AMQP: %s", message.payload().length() > 50
                        ? message.payload().substring(0, 50) + "..." : message.payload());
                }
            });
    }

    @Override
    public boolean isAvailable() {
        return emitter != null;
    }
}
