package de.augmentia.quad.core.hitl.checkpoint;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaCheckpointChannelTest {

    @Test
    void notify_publishesJsonPayloadWithCheckpointData() {
        AtomicReference<String> received = new AtomicReference<>();
        KafkaCheckpointChannel.registerSender(received::set);

        var channel = new KafkaCheckpointChannel();
        var cp = new Checkpoint("kafka-1", "session-9", "executeBash", "{\"cmd\":\"ls\"}");

        channel.notify(cp);

        assertNotNull(received.get());
        assertTrue(received.get().contains("\"checkpointId\":\"kafka-1\""));
        assertTrue(received.get().contains("\"sessionId\":\"session-9\""));
        assertTrue(received.get().contains("\"toolName\":\"executeBash\""));
        assertTrue(received.get().contains("\"type\":\"hitl.checkpoint\""));
        assertTrue(received.get().contains("/api/checkpoints/kafka-1/approve"));
    }

    @Test
    void notify_noSenderRegistered_doesNotThrow() {
        KafkaCheckpointChannel.registerSender(null);
        var channel = new KafkaCheckpointChannel();
        var cp = new Checkpoint("kafka-2", "session-9", "executeBash", "{}");
        channel.notify(cp);
    }

    @Test
    void customPayloadFactory_isUsed() {
        List<String> sent = new ArrayList<>();
        KafkaCheckpointChannel.registerSender(sent::add);

        var channel = new KafkaCheckpointChannel();
        channel.setPayloadFactory(cp -> "custom:" + cp.id());
        channel.notify(new Checkpoint("kafka-3", "session-9", "executeBash", "{}"));

        assertEquals(List.of("custom:kafka-3"), sent);
    }

    @Test
    void defaultPayload_containsCreatedAtIsoInstant() {
        var cp = new Checkpoint("kafka-4", "session-9", "executeBash", "{}");
        String payload = KafkaCheckpointChannel.defaultPayload(cp);
        assertTrue(payload.contains("\"status\":\"PENDING\""));
        assertNotNull(cp.createdAt());
        assertTrue(payload.contains("\"createdAt\":\""));
    }
}