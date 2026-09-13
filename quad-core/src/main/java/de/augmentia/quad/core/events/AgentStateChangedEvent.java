package de.augmentia.quad.core.events;

import java.time.Instant;

public record AgentStateChangedEvent(
    String sessionId,
    Instant timestamp,
    String previousPhase,
    String currentPhase,
    String goal
) implements AgentEvent {}
