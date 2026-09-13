package de.augmentia.quad.core.session.memory;

import de.augmentia.quad.core.message.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlidingWindowConversationManagerTest {

    @Test
    void windowSizeOneRequiresAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowConversationManager(0));
    }

    @Test
    void returnsAllMessagesIfWithinWindow() {
        var mgr = new SlidingWindowConversationManager(5);
        List<Message> msgs = List.of(
            Message.user("u1"),
            Message.assistant("a1"),
            Message.user("u2")
        );
        assertEquals(3, mgr.prune(msgs).size());
    }

    @Test
    void prunesNonSystemMessagesWhenExceedingWindow() {
        var mgr = new SlidingWindowConversationManager(2);
        List<Message> msgs = List.of(
            Message.user("u1"),
            Message.user("u2"),
            Message.user("u3"),
            Message.user("u4")
        );
        List<Message> pruned = mgr.prune(msgs);
        assertEquals(2, pruned.size());
        assertEquals("u3", pruned.get(0).content());
        assertEquals("u4", pruned.get(1).content());
    }

    @Test
    void preservesSystemMessages() {
        var mgr = new SlidingWindowConversationManager(2);
        List<Message> msgs = List.of(
            Message.system("sys"),
            Message.user("u1"),
            Message.user("u2"),
            Message.user("u3")
        );
        List<Message> pruned = mgr.prune(msgs);
        assertEquals(3, pruned.size());
        assertTrue(pruned.get(0).isSystem());
        assertEquals("u2", pruned.get(1).content());
        assertEquals("u3", pruned.get(2).content());
    }

    @Test
    void keepsSystemMessagesEvenIfWindowExceeded() {
        var mgr = new SlidingWindowConversationManager(1);
        List<Message> msgs = List.of(
            Message.system("s1"),
            Message.system("s2"),
            Message.user("u1"),
            Message.user("u2")
        );
        List<Message> pruned = mgr.prune(msgs);
        assertEquals(3, pruned.size());
        assertTrue(pruned.get(0).isSystem());
        assertTrue(pruned.get(1).isSystem());
        assertEquals("u2", pruned.get(2).content());
    }

    @Test
    void emptyListReturnsEmpty() {
        var mgr = new SlidingWindowConversationManager(5);
        assertTrue(mgr.prune(List.of()).isEmpty());
    }

    @Test
    void singleMessageStaysWithinWindow() {
        var mgr = new SlidingWindowConversationManager(3);
        List<Message> pruned = mgr.prune(List.of(Message.user("only")));
        assertEquals(1, pruned.size());
    }

    @Test
    void nonSystemWithinWindowNotPruned() {
        var mgr = new SlidingWindowConversationManager(10);
        List<Message> msgs = List.of(
            Message.system("sys"),
            Message.user("u1"),
            Message.assistant("a1")
        );
        assertEquals(3, mgr.prune(msgs).size());
    }
}
