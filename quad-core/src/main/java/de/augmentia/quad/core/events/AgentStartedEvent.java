package de.augmentia.quad.core.events;

import java.time.Instant;

public record AgentStartedEvent(
    String sessionId,
    Instant timestamp,
    String initialPrompt
) implements AgentEvent {}
