package de.augmentia.quad.core.agent.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record QuadMessage(
    String id,
    String sessionId,
    String source,
    String contentType,
    String payload,
    Map<String, String> headers,
    Instant timestamp
) {
    public static QuadMessage of(String source, String contentType, String payload) {
        return new QuadMessage(
            UUID.randomUUID().toString(),
            null,
            source,
            contentType,
            payload,
            Map.of(),
            Instant.now()
        );
    }

    public static QuadMessage command(String command) {
        return new QuadMessage(
            UUID.randomUUID().toString(),
            null,
            "api",
            "command",
            command,
            Map.of(),
            Instant.now()
        );
    }

    public QuadMessage withSession(String sessionId) {
        return new QuadMessage(id, sessionId, source, contentType, payload, headers, timestamp);
    }

    public QuadMessage withPayload(String payload) {
        return new QuadMessage(id, sessionId, source, contentType, payload, headers, timestamp);
    }

    public QuadMessage withHeaders(Map<String, String> headers) {
        return new QuadMessage(id, sessionId, source, contentType, payload, headers, timestamp);
    }
}
