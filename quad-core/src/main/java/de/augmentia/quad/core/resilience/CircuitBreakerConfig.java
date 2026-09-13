package de.augmentia.quad.core.resilience;

public record CircuitBreakerConfig(
    float failureRateThreshold,
    long slidingWindowSeconds,
    long halfOpenDelaySeconds
) {
    public static final CircuitBreakerConfig DEFAULT = new CircuitBreakerConfig(0.5f, 10, 30);
}
