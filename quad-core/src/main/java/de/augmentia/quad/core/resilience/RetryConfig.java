package de.augmentia.quad.core.resilience;

public record RetryConfig(
    int maxAttempts,
    long backoffDelayMs,
    double backoffMultiplier
) {
    public static final RetryConfig DEFAULT = new RetryConfig(3, 1000, 2.0);
}
