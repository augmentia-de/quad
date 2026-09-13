package de.augmentia.quad.core.resilience;

import java.time.Duration;

/**
 * Aggregated resilience configuration combining retry, circuit breaker, and model timeout settings.
 */
public record ResilienceConfig(
    RetryConfig retryConfig,
    CircuitBreakerConfig circuitBreakerConfig,
    Duration modelTimeout
) {
    public static final ResilienceConfig DEFAULT = new ResilienceConfig(RetryConfig.DEFAULT, CircuitBreakerConfig.DEFAULT, null);
    public static final ResilienceConfig NONE = new ResilienceConfig(null, null, null);

    /**
     * Convenience constructor without model timeout.
     */
    public ResilienceConfig(RetryConfig retryConfig, CircuitBreakerConfig circuitBreakerConfig) {
        this(retryConfig, circuitBreakerConfig, null);
    }
}
