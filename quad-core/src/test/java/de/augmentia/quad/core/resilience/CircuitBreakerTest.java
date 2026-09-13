package de.augmentia.quad.core.resilience;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    @Test
    void startsInClosedState() {
        var cb = new CircuitBreaker(CircuitBreakerConfig.DEFAULT);
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void staysClosedOnSuccess() throws Exception {
        var cb = new CircuitBreaker(CircuitBreakerConfig.DEFAULT);
        String result = cb.call(() -> "ok", () -> "fallback");
        assertEquals("ok", result);
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void opensAfterFailureThresholdExceeded() throws Exception {
        var config = new CircuitBreakerConfig(0.5f, 10, 30);
        var cb = new CircuitBreaker(config);

        // 3 failures out of 5 = 60% > 50% threshold
        for (int i = 0; i < 3; i++) {
            try { cb.call(() -> { throw new RuntimeException("fail"); }, () -> null); } catch (Exception ignored) {}
        }
        for (int i = 0; i < 2; i++) {
            cb.call(() -> "ok", () -> null);
        }

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    void openStateUsesFallback() throws Exception {
        var config = new CircuitBreakerConfig(0.5f, 10, 30);
        var cb = new CircuitBreaker(config);

        // Force open
        for (int i = 0; i < 10; i++) {
            try { cb.call(() -> { throw new RuntimeException("fail"); }, () -> null); } catch (Exception ignored) {}
        }

        String result = cb.call(() -> "should not run", () -> "fallback");
        assertEquals("fallback", result);
    }

    @Test
    void resetReturnsToClosed() throws Exception {
        var config = new CircuitBreakerConfig(0.5f, 10, 30);
        var cb = new CircuitBreaker(config);

        for (int i = 0; i < 10; i++) {
            try { cb.call(() -> { throw new RuntimeException("fail"); }, () -> null); } catch (Exception ignored) {}
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        cb.reset();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void halfOpenAfterDelay() throws Exception {
        var config = new CircuitBreakerConfig(0.5f, 10, 0); // 0 second delay for fast test
        var cb = new CircuitBreaker(config);

        // Force open
        for (int i = 0; i < 10; i++) {
            try { cb.call(() -> { throw new RuntimeException("fail"); }, () -> null); } catch (Exception ignored) {}
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Should transition to HALF_OPEN on next call (delay=0)
        String result = cb.call(() -> "half-open-success", () -> "fallback");
        assertEquals("half-open-success", result);
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void halfOpenFailureReturnsToOpen() throws Exception {
        var config = new CircuitBreakerConfig(0.5f, 10, 0);
        var cb = new CircuitBreaker(config);

        // Force open
        for (int i = 0; i < 10; i++) {
            try { cb.call(() -> { throw new RuntimeException("fail"); }, () -> null); } catch (Exception ignored) {}
        }

        // Half-open test: failure returns to OPEN
        try { cb.call(() -> { throw new RuntimeException("still broken"); }, () -> null); } catch (Exception ignored) {}
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    void exceptionPropagatesFromCall() {
        var cb = new CircuitBreaker(CircuitBreakerConfig.DEFAULT);
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            cb.call(() -> { throw new RuntimeException("boom"); }, () -> null)
        );
        assertEquals("boom", ex.getMessage());
    }
}
