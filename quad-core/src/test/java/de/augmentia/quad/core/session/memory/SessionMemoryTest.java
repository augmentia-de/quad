package de.augmentia.quad.core.session.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionMemoryTest {

    @Test
    void records_messages_within_window() {
        SessionMemory memory = new SessionMemory(10);
        memory.record(List.of(UserMessage.from("hallo"), AiMessage.from("hi")));

        assertEquals(2, memory.history().size());
        assertFalse(memory.hasSummary());
    }

    @Test
    void compresses_overflow_into_summary_instead_of_dropping() {
        SessionMemory memory = new SessionMemory(3);

        memory.record(List.of(
            SystemMessage.from("s1"), UserMessage.from("u1"), AiMessage.from("a1")));
        assertFalse(memory.hasSummary());

        // Überlauf: alte Nachrichten werden in die Summary komprimiert
        memory.record(List.of(UserMessage.from("u2"), AiMessage.from("a2")));

        assertTrue(memory.hasSummary());
        assertTrue(memory.summary().contains("s1"));
        System.out.println(memory.summary());
    }

    @Test
    void clear_resets_history_and_summary() {
        SessionMemory memory = new SessionMemory(2);
        memory.record(List.of(
            SystemMessage.from("a"), UserMessage.from("b"), UserMessage.from("c")));
        memory.clear();

        assertTrue(memory.history().isEmpty());
        assertFalse(memory.hasSummary());
    }

    @Test
    void ignores_null_or_empty_batches() {
        SessionMemory memory = new SessionMemory(5);
        memory.record(null);
        memory.record(List.of());
        assertTrue(memory.history().isEmpty());
    }
}