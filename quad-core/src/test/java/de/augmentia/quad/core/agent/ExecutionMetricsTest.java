package de.augmentia.quad.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionMetricsTest {

    @Test
    void recordCreation() {
        ExecutionMetrics metrics = new ExecutionMetrics(250, 1000, 500, 3);
        assertEquals(250, metrics.durationMs());
        assertEquals(1000, metrics.inputTokens());
        assertEquals(500, metrics.outputTokens());
        assertEquals(3, metrics.toolCallsCount());
    }

    @Test
    void zeroValues() {
        ExecutionMetrics metrics = new ExecutionMetrics(0, 0, 0, 0);
        assertEquals(0, metrics.durationMs());
        assertEquals(0, metrics.inputTokens());
        assertEquals(0, metrics.outputTokens());
        assertEquals(0, metrics.toolCallsCount());
    }
}
