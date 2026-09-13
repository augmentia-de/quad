package de.augmentia.quad.core.resilience;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryTest {

    @Test
    void succeedsOnFirstAttempt() throws Exception {
        String result = Retry.run(() -> "ok", new RetryConfig(3, 0, 1.0));
        assertEquals("ok", result);
    }

    @Test
    void retriesAndSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        String result = Retry.run(() -> {
            if (attempts.incrementAndGet() < 3) throw new RuntimeException("transient");
            return "recovered";
        }, new RetryConfig(3, 0, 1.0));
        assertEquals("recovered", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void throwsAfterMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            Retry.run(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("persistent");
            }, new RetryConfig(3, 0, 1.0))
        );
        assertEquals("persistent", ex.getMessage());
        assertEquals(3, attempts.get());
    }

    @Test
    void nonRetryableExceptionThrowsImmediately() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () ->
            Retry.run(() -> {
                attempts.incrementAndGet();
                throw new IllegalArgumentException("bad arg");
            }, new RetryConfig(3, 0, 1.0))
        );
        assertEquals(1, attempts.get());
    }

    @Test
    void authErrorNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(RuntimeException.class, () ->
            Retry.run(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("authentication failed");
            }, new RetryConfig(3, 0, 1.0))
        );
        assertEquals(1, attempts.get());
    }

    @Test
    void customRetryableCheck() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        String result = Retry.run(() -> {
            if (attempts.incrementAndGet() < 3) throw new RuntimeException("custom");
            return "done";
        }, new RetryConfig(3, 0, 1.0), e -> true);
        assertEquals("done", result);
    }

    @Test
    void customRetryableCheckRejects() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(RuntimeException.class, () ->
            Retry.run(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("nope");
            }, new RetryConfig(3, 0, 1.0), e -> false)
        );
        assertEquals(1, attempts.get());
    }

    @Test
    void configDefaults() {
        assertEquals(3, RetryConfig.DEFAULT.maxAttempts());
        assertEquals(1000, RetryConfig.DEFAULT.backoffDelayMs());
        assertEquals(2.0, RetryConfig.DEFAULT.backoffMultiplier());
    }
}
