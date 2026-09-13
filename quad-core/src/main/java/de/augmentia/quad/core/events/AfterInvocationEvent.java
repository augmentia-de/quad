package de.augmentia.quad.core.events;

import java.time.Instant;

public record AfterInvocationEvent(
    String sessionId,
    Instant timestamp
) implements AgentEvent {}
