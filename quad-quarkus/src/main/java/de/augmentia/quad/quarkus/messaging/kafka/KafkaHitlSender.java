package de.augmentia.quad.quarkus.messaging.kafka;

import de.augmentia.quad.core.hitl.checkpoint.KafkaCheckpointChannel;
import io.quarkus.arc.properties.IfBuildProperty;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

/**
 * Wires {@link KafkaCheckpointChannel} to a real Kafka topic via SmallRye
 * Reactive Messaging. Active only when {@code quad.hitl.kafka.enabled=true};
 * the outbound channel {@code quad-hitl-kafka} is defined in
 * {@code application-kafka.properties}.
 */
@ApplicationScoped
@IfBuildProperty(name = "quad.hitl.kafka.enabled", stringValue = "true")
public class KafkaHitlSender {

    private static final Logger log = Logger.getLogger(KafkaHitlSender.class);

    @Channel("quad-hitl-kafka")
    Emitter<String> emitter;

    @PostConstruct
    void init() {
        KafkaCheckpointChannel.registerSender(payload -> {
            emitter.send(payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.errorf("HITL-Checkpoint konnte nicht an Kafka gesendet werden: %s", ex.getMessage());
                    } else {
                        log.debugf("HITL-Checkpoint an Kafka gesendet: %s",
                            payload.length() > 60 ? payload.substring(0, 60) + "..." : payload);
                    }
                });
        });
        log.infof("Kafka-HITL-Channel bereit (Topic via quad-hitl-kafka konfiguriert)");
    }
}