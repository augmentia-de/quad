package de.augmentia.quad.core.events;

import java.time.Instant;

/**
 * Fired after a model call completes. Carries the provider-reported token usage so observability
 * hooks can record real prompt/completion tokens (0 when the provider reports none).
 */
public record ModelRespondedEvent(
    String sessionId,
    Instant timestamp,
    int inputTokens,
    int outputTokens
) implements AgentEvent {}
