package de.augmentia.quad.core.guardrails;

/**
 * Result of a guardrail validation.
 */
public record GuardrailResult(
    boolean pass,
    String reason,
    String sanitized
) {

    public static GuardrailResult ok() {
        return new GuardrailResult(true, null, null);
    }

    public static GuardrailResult block(String reason) {
        return new GuardrailResult(false, reason, null);
    }

    public static GuardrailResult block(String reason, String sanitized) {
        return new GuardrailResult(false, reason, sanitized);
    }
}
