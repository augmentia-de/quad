package de.augmentia.quad.quarkus.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class TopicAgentMapping {

    private static final Logger log = Logger.getLogger(TopicAgentMapping.class);

    private final Map<String, String> topicToAgent = new ConcurrentHashMap<>();

    public TopicAgentMapping(
            @ConfigProperty(name = "quad.messaging.kafka.topics", defaultValue = "") Optional<String> kafkaTopics,
            @ConfigProperty(name = "quad.messaging.amqp.queues", defaultValue = "") Optional<String> amqpQueues,
            @ConfigProperty(name = "quad.messaging.email.agents", defaultValue = "") Optional<String> emailAgents) {
        parseMappings(kafkaTopics, "kafka");
        parseMappings(amqpQueues, "amqp");
        parseMappings(emailAgents, "email");
        log.infof("Topic-Agent mappings: %s", topicToAgent);
    }

    private void parseMappings(Optional<String> config, String transport) {
        if (config.isEmpty() || config.get().isBlank()) return;
        for (String entry : config.get().split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length == 3 && !parts[0].isBlank() && !parts[1].isBlank() && !parts[2].isBlank()) {
                // Multi-tenant format: tenantId:topic:agentId
                String key = transport + ":" + parts[0].trim() + ":" + parts[1].trim();
                topicToAgent.put(key, parts[2].trim());
            } else if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                // Legacy format: topic:agentId (default tenant)
                String key = transport + ":default:" + parts[0].trim();
                topicToAgent.put(key, parts[1].trim());
            }
        }
    }

    public String agentForTopic(String transport, String tenantId, String topic) {
        return topicToAgent.get(transport + ":" + tenantId + ":" + topic);
    }

    public String agentForTopic(String transport, String topic) {
        return agentForTopic(transport, "default", topic);
    }

    public String agentForTopic(String compositeKey) {
        return topicToAgent.get(compositeKey);
    }

    public Map<String, String> allMappings() {
        return Map.copyOf(topicToAgent);
    }
}
