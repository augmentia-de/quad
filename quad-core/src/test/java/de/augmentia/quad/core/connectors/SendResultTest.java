package de.augmentia.quad.core.connectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SendResultTest {

    @Test
    void successCarriesMessageIdOnly() {
        SendResult r = SendResult.success("1712345678.000001");
        assertTrue(r.ok());
        assertEquals("1712345678.000001", r.messageId());
        assertNull(r.error());
    }

    @Test
    void failureCarriesErrorOnly() {
        SendResult r = SendResult.failure("not_in_channel");
        assertFalse(r.ok());
        assertEquals("not_in_channel", r.error());
        assertNull(r.messageId());
    }

    @Test
    void failureErrorMessageMayBeNull() {
        assertFalse(SendResult.failure(null).ok());
    }
}