package de.augmentia.quad.core.resilience;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenRecoveryTest {

    private TokenRecovery recovery;

    @BeforeEach
    void setUp() {
        recovery = new TokenRecovery();
    }

    @Test
    void detectsTokenLimitErrors() {
        assertTrue(TokenRecovery.isTokenLimitError(
            new RuntimeException("maximum context length exceeded")));
        assertTrue(TokenRecovery.isTokenLimitError(
            new RuntimeException("context_length_exceeded")));
        assertTrue(TokenRecovery.isTokenLimitError(
            new RuntimeException("max_tokens reached")));
        assertTrue(TokenRecovery.isTokenLimitError(
            new RuntimeException("token limit exceeded")));
        assertTrue(TokenRecovery.isTokenLimitError(
            new RuntimeException("too many tokens")));
        assertTrue(TokenRecovery.isTokenLimitError(
            new RuntimeException("request too large")));
        assertTrue(TokenRecovery.isTokenLimitError(
            new RuntimeException("400 bad request context")));
    }

    @Test
    void nonTokenErrorsNotDetected() {
        assertFalse(TokenRecovery.isTokenLimitError(new RuntimeException("connection refused")));
        assertFalse(TokenRecovery.isTokenLimitError(null));
        assertFalse(TokenRecovery.isTokenLimitError(new RuntimeException((String) null)));
    }

    @Test
    void recoverHalvesNonSystemMessages() {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);
        memory.add(SystemMessage.from("sys"));
        memory.add(UserMessage.from("u1"));
        memory.add(AiMessage.from("a1"));
        memory.add(UserMessage.from("u2"));
        memory.add(AiMessage.from("a2"));
        memory.add(UserMessage.from("u3"));
        memory.add(AiMessage.from("a3"));

        boolean recovered = recovery.recover(memory);
        assertTrue(recovered);

        var messages = memory.messages();
        // System kept + last half of non-system (4/2=2 kept from end: u3,a3)
        assertTrue(messages.size() < 7);
        assertTrue(messages.get(0) instanceof SystemMessage);
    }

    @Test
    void recoverReturnsFalseWhenTooFewMessages() {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);
        memory.add(UserMessage.from("only one"));
        assertFalse(recovery.recover(memory));
    }

    @Test
    void recoverReturnsFalseWhenOnlySystemMessages() {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);
        memory.add(SystemMessage.from("s1"));
        memory.add(SystemMessage.from("s2"));
        assertFalse(recovery.recover(memory));
    }

    @Test
    void recoverReturnsFalseAfterMaxAttempts() {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);
        memory.add(UserMessage.from("u1"));
        memory.add(UserMessage.from("u2"));

        assertTrue(recovery.recover(memory));
        assertTrue(recovery.recover(memory));
        assertTrue(recovery.recover(memory));
        assertFalse(recovery.recover(memory));
        assertEquals(3, recovery.attempts());
    }

    @Test
    void resetClearsAttempts() {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);
        memory.add(UserMessage.from("u1"));
        memory.add(UserMessage.from("u2"));

        recovery.recover(memory);
        recovery.recover(memory);
        recovery.recover(memory);
        assertFalse(recovery.recover(memory));

        recovery.reset();
        assertEquals(0, recovery.attempts());
        assertTrue(recovery.recover(memory));
    }

    @Test
    void systemMessagesPreservedAfterRecovery() {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);
        memory.add(SystemMessage.from("important sys"));
        memory.add(UserMessage.from("u1"));
        memory.add(AiMessage.from("a1"));
        memory.add(UserMessage.from("u2"));
        memory.add(AiMessage.from("a2"));
        memory.add(UserMessage.from("u3"));

        recovery.recover(memory);
        var messages = memory.messages();
        boolean hasSys = messages.stream().anyMatch(m ->
            m instanceof SystemMessage s && s.text().equals("important sys"));
        assertTrue(hasSys);
    }
}
