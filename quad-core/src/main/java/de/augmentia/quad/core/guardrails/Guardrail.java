package de.augmentia.quad.core.guardrails;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * Validates a sequence of chat messages and returns a decision.
 */
public interface Guardrail {
    GuardrailResult validate(List<ChatMessage> messages, String context);
}
