package de.augmentia.quad.core.events;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.time.Instant;

public record ToolExecutionStartedEvent(
    String sessionId,
    Instant timestamp,
    ToolExecutionRequest toolExecutionRequest
) implements AgentEvent {}
