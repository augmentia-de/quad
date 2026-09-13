package de.augmentia.quad.core.hitl.checkpoint;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Asynchronous HITL notification channel that publishes checkpoint events to a
 * message bus (Kafka or any queue/bus). The transport is decoupled via a
 * registered {@link MessageSender}, so this class stays framework-agnostic in
 * the core module — the runtime (e.g. Quarkus) wires it to the real broker.
 *
 * <p>Consumers of the published checkpoint can approve/reject it via the REST
 * endpoints (or a dedicated response topic), the checkpoint id is carried in
 * every payload.
 */
public class KafkaCheckpointChannel implements CheckpointChannel {

    private static final Logger log = LoggerFactory.getLogger(KafkaCheckpointChannel.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @FunctionalInterface
    public interface MessageSender {
        /** Publishes the JSON payload to the configured topic/bus. */
        void send(String jsonPayload);
    }

    public interface PayloadFactory {
        String toPayload(Checkpoint checkpoint);
    }

    private static MessageSender senderStub = json ->
        log.warn("KafkaCheckpointChannel: Kein Sender registriert! Checkpoint-Meldung verloren: {}", json);

    private static final PayloadFactory defaultPayloadFactory = KafkaCheckpointChannel::defaultPayload;

    private volatile PayloadFactory payloadFactory = defaultPayloadFactory;

    public static void registerSender(MessageSender sender) {
        if (sender != null) senderStub = sender;
    }

    /** Allows customizing the published payload (e.g. add routing headers). */
    public void setPayloadFactory(PayloadFactory payloadFactory) {
        if (payloadFactory != null) this.payloadFactory = payloadFactory;
    }

    @Override
    public void notify(Checkpoint checkpoint) {
        log.info("KafkaCheckpointChannel: Publishing checkpoint {}", checkpoint.id());
        try {
            senderStub.send(payloadFactory.toPayload(checkpoint));
        } catch (Exception e) {
            log.warn("KafkaCheckpointChannel: failed to send for {}: {}",
                checkpoint.id(), e.getMessage());
        }
    }

    static String defaultPayload(Checkpoint checkpoint) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "hitl.checkpoint");
        body.put("checkpointId", checkpoint.id());
        body.put("sessionId", checkpoint.sessionId());
        body.put("toolName", checkpoint.toolName());
        body.put("arguments", checkpoint.arguments() != null ? checkpoint.arguments() : "{}");
        body.put("status", checkpoint.status().name());
        body.put("createdAt", checkpoint.createdAt() != null ? checkpoint.createdAt().toString() : null);
        body.put("approveEndpoint", "/api/checkpoints/" + checkpoint.id() + "/approve");
        body.put("rejectEndpoint", "/api/checkpoints/" + checkpoint.id() + "/reject");
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"hitl.checkpoint\",\"checkpointId\":\"" + checkpoint.id() + "\"}";
        }
    }
}