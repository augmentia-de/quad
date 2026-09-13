package de.augmentia.quad.core.agent;

public record ExecutionMetrics(
    long durationMs,
    int inputTokens,
    int outputTokens,
    int toolCallsCount
) {}
