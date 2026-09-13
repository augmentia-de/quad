package de.augmentia.quad.core.connectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageTargetTest {

    @Test
    void parsesPlainTarget() {
        MessageTarget t = MessageTarget.parse("telegram:12345");
        assertEquals("telegram", t.platform());
        assertEquals("12345", t.chatId());
        assertNull(t.threadId());
    }

    @Test
    void parsesThreadTarget() {
        MessageTarget t = MessageTarget.parse("slack:C0123456:1712345678.123456");
        assertEquals("slack", t.platform());
        assertEquals("C0123456", t.chatId());
        assertEquals("1712345678.123456", t.threadId());
    }

    @Test
    void keepsColonsInsideThreadPart() {
        MessageTarget t = MessageTarget.parse("slack:T123/C456:a:b:c");
        assertEquals("slack", t.platform());
        assertEquals("T123/C456", t.chatId());
        assertEquals("a:b:c", t.threadId());
    }

    @Test
    void formatRoundTrips() {
        assertEquals("slack:C0123456", new MessageTarget("slack", "C0123456", null).format());
        assertEquals("telegram:12345", new MessageTarget("telegram", "12345", "").format());
        assertEquals("slack:C0123456:1712345678.1",
            new MessageTarget("slack", "C0123456", "1712345678.1").format());
        assertEquals("slack:T123/C456:1712345678.1",
            new MessageTarget("slack", "T123/C456", "1712345678.1").format());
    }

    @Test
    void nullOrBlankTargetRejected() {
        assertThrows(IllegalArgumentException.class, () -> MessageTarget.parse(null));
        assertThrows(IllegalArgumentException.class, () -> MessageTarget.parse(""));
        assertThrows(IllegalArgumentException.class, () -> MessageTarget.parse("  "));
    }

    @Test
    void missingPartsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MessageTarget.parse("slack"));
        assertThrows(IllegalArgumentException.class, () -> MessageTarget.parse("slack:"));
        assertThrows(IllegalArgumentException.class, () -> MessageTarget.parse(":C0123456"));
        assertThrows(IllegalArgumentException.class, () -> MessageTarget.parse(":"));
    }

    @Test
    void trailingColonYieldsNullThread() {
        MessageTarget t = MessageTarget.parse("slack:C0123456:");
        assertEquals("slack", t.platform());
        assertEquals("C0123456", t.chatId());
        assertNull(t.threadId());
    }
}