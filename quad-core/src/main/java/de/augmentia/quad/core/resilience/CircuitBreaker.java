package de.augmentia.quad.core.resilience;

import java.time.Instant;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    static final int MAX_RECENT_CALLS = 100;

    private final CircuitBreakerConfig config;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final Queue<Boolean> recentCalls = new LinkedList<>();
    private Instant openedAt;
    private volatile boolean halfOpenTestInProgress;

    public CircuitBreaker(CircuitBreakerConfig config) {
        this.config = config;
    }

    public <T> T call(Callable<T> callable, Callable<T> fallback) throws Exception {
        var currentState = state.get();

        if (currentState == State.OPEN) {
            if (openedAt != null && openedAt.plusSeconds(config.halfOpenDelaySeconds()).isBefore(Instant.now())) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenTestInProgress = false;
                    currentState = State.HALF_OPEN;
                } else {
                    currentState = state.get();
                }
            } else {
                return fallback.call();
            }
        }

        if (currentState == State.HALF_OPEN && halfOpenTestInProgress) {
            return fallback.call();
        }

        try {
            if (currentState == State.HALF_OPEN) {
                halfOpenTestInProgress = true;
            }

            T result = callable.call();

            recordSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            if (currentState == State.HALF_OPEN) {
                state.set(State.OPEN);
                openedAt = Instant.now();
            }
            throw e;
        }
    }

    public State getState() {
        return state.get();
    }

    private synchronized void recordSuccess() {
        if (state.get() == State.HALF_OPEN) {
            state.set(State.CLOSED);
            halfOpenTestInProgress = false;
            recentCalls.clear();
            return;
        }
        recentCalls.add(true);
        evictOldCalls();
    }

    private synchronized void recordFailure() {
        recentCalls.add(false);
        evictOldCalls();

        if (state.get() == State.CLOSED) {
            var failureRate = calculateFailureRate();
            if (failureRate > config.failureRateThreshold()) {
                state.set(State.OPEN);
                openedAt = Instant.now();
            }
        } else if (state.get() == State.HALF_OPEN) {
            halfOpenTestInProgress = false;
        }
    }

    private void evictOldCalls() {
        while (!recentCalls.isEmpty()) {
            if (recentCalls.size() > MAX_RECENT_CALLS) {
                recentCalls.poll();
            } else {
                break;
            }
        }
    }

    private double calculateFailureRate() {
        if (recentCalls.isEmpty()) return 0.0;
        long failures = recentCalls.stream().filter(b -> !b).count();
        return (double) failures / recentCalls.size();
    }

    public void reset() {
        state.set(State.CLOSED);
        synchronized (this) {
            recentCalls.clear();
        }
        openedAt = null;
        halfOpenTestInProgress = false;
    }
}
