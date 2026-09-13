package de.augmentia.quad.core.events;

import dev.langchain4j.data.message.ChatMessage;

import java.time.Instant;
import java.util.List;

public record ModelRequestedEvent(
    String sessionId,
    Instant timestamp,
    List<ChatMessage> promptHistory
) implements AgentEvent {}
