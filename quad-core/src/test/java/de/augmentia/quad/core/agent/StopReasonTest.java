package de.augmentia.quad.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StopReasonTest {

    @Test
    void allValuesExist() {
        assertEquals(5, StopReason.values().length);
        assertNotNull(StopReason.MAX_ITERATIONS);
        assertNotNull(StopReason.COMPLETED);
        assertNotNull(StopReason.INTERRUPTED);
        assertNotNull(StopReason.ERROR);
        assertNotNull(StopReason.STUCK);
    }

    @Test
    void valueOfMatches() {
        assertEquals(StopReason.COMPLETED, StopReason.valueOf("COMPLETED"));
        assertEquals(StopReason.ERROR, StopReason.valueOf("ERROR"));
    }
}
