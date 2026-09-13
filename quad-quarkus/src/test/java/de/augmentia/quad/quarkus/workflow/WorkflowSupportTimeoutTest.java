package de.augmentia.quad.quarkus.workflow;

import de.augmentia.quad.quarkus.workflow.internal.WorkflowSupport;
import de.augmentia.quad.quarkus.workflow.internal.WorkflowSupport.TimeoutResult;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowSupportTimeoutTest {

    private final WorkflowSupport support = new WorkflowSupport();

    @Test
    void returnsValueWhenCallFinishesInTime() {
        TimeoutResult result = support.runWithTimeout(2000, () -> "hello");
        assertFalse(result.timedOut());
        assertEquals("hello", result.value());
    }

    @Test
    void returnsTimedOutWhenCallExceedsLimit() {
        TimeoutResult result = support.runWithTimeout(100, () -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                // interrupted by cancel(true) — acceptable
            }
            return "late";
        });
        assertTrue(result.timedOut());
    }

    @Test
    void cancelsFutureWithoutLeavingWaitingThread() {
        AtomicLong completedAt = new AtomicLong(0);
        long start = System.nanoTime();
        TimeoutResult result = support.runWithTimeout(100, () -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                // unterbrochen
            }
            completedAt.set(System.nanoTime());
            return "late";
        });
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(result.timedOut());
        // The waiter must not return after the sleep, but immediately with abort.
        assertTrue(elapsedMs < 4000, "caller freed too late: " + elapsedMs + "ms");
    }

    @Test
    void zeroTimeoutRunsSynchronously() {
        TimeoutResult result = support.runWithTimeout(0, () -> "sync");
        assertFalse(result.timedOut());
        assertEquals("sync", result.value());
    }
}