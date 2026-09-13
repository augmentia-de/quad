package de.augmentia.quad.core.guardrails;

/**
 * Action to take when a guardrail blocks a request.
 */
public enum BlockAction {
    /** Throw a {@link GuardrailException}. */
    THROW,
    /** Replace the request/response with a fallback message. */
    FALLBACK,
    /** Escalate to a human (HITL approval flow). */
    ESCALATE
}
