package de.augmentia.quad.core.events;

import java.time.Instant;

public record TokenEvent(
    String sessionId,
    Instant timestamp,
    String token
) implements AgentEvent {}
