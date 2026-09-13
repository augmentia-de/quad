package de.augmentia.quad.core.events;

import java.time.Instant;

public record BeforeInvocationEvent(
    String sessionId,
    Instant timestamp
) implements AgentEvent {}
