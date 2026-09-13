package de.augmentia.quad.core.events;

import java.time.Instant;

public record AgentFinishedEvent(
    String sessionId,
    Instant timestamp,
    String finalAnswer,
    String structuredOutput
) implements AgentEvent {

    /** Backward-compatible constructor without structured output. */
    public AgentFinishedEvent(String sessionId, Instant timestamp, String finalAnswer) {
        this(sessionId, timestamp, finalAnswer, null);
    }
}
