package de.augmentia.quad.quarkus.messaging.kafka;

import de.augmentia.quad.core.agent.messaging.InboundChannel;
import de.augmentia.quad.core.agent.messaging.QuadMessage;
import de.augmentia.quad.quarkus.messaging.MessagingRouter;
import de.augmentia.quad.quarkus.messaging.TopicAgentMapping;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
@IfBuildProperty(name = "quad.messaging.kafka.inbound.enabled", stringValue = "true")
public class KafkaInboundChannel implements InboundChannel {

    private static final Logger log = Logger.getLogger(KafkaInboundChannel.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile KafkaConsumer<String, String> consumer;
    private volatile Thread consumerThread;

    @Inject
    TopicAgentMapping mapping;

    @Inject
    MessagingRouter router;

    @ConfigProperty(name = "quad.messaging.kafka.inbound.group-id", defaultValue = "quad-inbound")
    String groupId;

    @ConfigProperty(name = "quad.messaging.kafka.inbound.bootstrap-servers", defaultValue = "localhost:9092")
    String bootstrapServers;

    @ConfigProperty(name = "quad.messaging.kafka.inbound.auto-offset-reset", defaultValue = "earliest")
    String autoOffsetReset;

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public void start(MessageHandler handler) {
        Set<String> topics = resolveTopics();
        if (topics.isEmpty()) {
            log.warn("Kafka inbound enabled but no topics configured (quad.messaging.kafka.topics), skipping");
            return;
        }

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", groupId);
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("auto.offset.reset", autoOffsetReset);
        props.put("enable.auto.commit", "true");

        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(topics);

        running.set(true);
        consumerThread = new Thread(this::pollLoop, "kafka-inbound");
        consumerThread.setDaemon(true);
        consumerThread.start();

        log.infof("Kafka inbound started, subscribed to: %s", topics);
    }

    @Override
    public void stop() {
        running.set(false);
        if (consumer != null) {
            consumer.wakeup();
        }
        if (consumerThread != null) {
            try {
                consumerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Kafka inbound stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void pollLoop() {
        try {
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                for (ConsumerRecord<String, String> record : records) {
                    handleTopic(record.topic(), record.value());
                }
            }
        } catch (WakeupException e) {
            // Expected on shutdown
        } catch (Exception e) {
            log.errorf("Kafka consumer error: %s", e.getMessage());
        } finally {
            if (consumer != null) {
                try {
                    consumer.close();
                } catch (Exception e) {
                    log.debugf("Error closing Kafka consumer: %s", e.getMessage());
                }
            }
        }
    }

    private void handleTopic(String topicName, String payload) {
        if (!running.get()) {
            log.warn("Kafka message received but channel not running, discarding");
            return;
        }

        String tenantId = resolveTenant(topicName);
        String agentId = mapping.agentForTopic("kafka", tenantId, topicName);
        if (agentId == null) {
            log.warnf("No agent mapped for Kafka topic: %s (tenant=%s), discarding message", topicName, tenantId);
            return;
        }

        log.infof("Kafka message on topic %s (agentId=%s): %s", topicName, agentId,
            payload.length() > 100 ? payload.substring(0, 100) + "..." : payload);

        QuadMessage message = new QuadMessage(
            UUID.randomUUID().toString(),
            null,
            "kafka:" + topicName,
            "text",
            payload,
            Map.of("topic", topicName, "agentId", agentId, "tenantId", tenantId),
            java.time.Instant.now()
        );

        router.handleMessage(message);
    }

    private Set<String> resolveTopics() {
        Set<String> topics = new LinkedHashSet<>();
        for (String key : mapping.allMappings().keySet()) {
            if (key.startsWith("kafka:")) {
                // key format: "kafka:tenantId:topicName"
                String[] parts = key.split(":");
                if (parts.length == 3 && !parts[2].isBlank()) {
                    topics.add(parts[2]);
                }
            }
        }
        return topics;
    }

    private String resolveTenant(String topicName) {
        String envKey = "QUAD_MESSAGING_KAFKA_TOPIC_" +
            topicName.toUpperCase().replace('-', '_') + "_TENANT";
        return System.getProperty(
            "quad.messaging.kafka.topic." + topicName + ".tenant",
            System.getenv().getOrDefault(envKey, "default"));
    }
}
