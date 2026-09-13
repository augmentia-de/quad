package de.augmentia.quad.core.guardrails;

/**
 * Raised when a guardrail blocks a request with {@link BlockAction#THROW}.
 */
public class GuardrailException extends RuntimeException {

    private final String guardrailReason;

    public GuardrailException(String guardrailReason) {
        super("Guardrail block: " + guardrailReason);
        this.guardrailReason = guardrailReason;
    }

    public String guardrailReason() {
        return guardrailReason;
    }
}
