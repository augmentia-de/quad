package de.augmentia.quad.core.events;

import java.time.Instant;

public record ToolExecutionFinishedEvent(
    String sessionId,
    Instant timestamp,
    String toolName,
    boolean isError,
    String result
) implements AgentEvent {}
