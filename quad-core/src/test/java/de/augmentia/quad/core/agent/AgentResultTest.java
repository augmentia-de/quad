package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.message.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentResultTest {

    @Test
    void fullConstructor() {
        ExecutionMetrics metrics = new ExecutionMetrics(100, 50, 30, 2);
        AgentResult result = new AgentResult(
            "sess-1", "answer", List.of(), metrics, StopReason.COMPLETED, "{\"key\":\"val\"}"
        );
        assertEquals("sess-1", result.sessionId());
        assertEquals("answer", result.finalAnswer());
        assertEquals(metrics, result.metrics());
        assertEquals(StopReason.COMPLETED, result.stopReason());
        assertEquals("{\"key\":\"val\"}", result.structuredOutput());
        assertTrue(result.hasStructuredOutput());
    }

    @Test
    void constructorWithoutStructuredOutput() {
        ExecutionMetrics metrics = new ExecutionMetrics(50, 0, 0, 0);
        AgentResult result = new AgentResult("s", "ans", List.of(), metrics, StopReason.COMPLETED);
        assertFalse(result.hasStructuredOutput());
        assertNull(result.structuredOutput());
    }

    @Test
    void backwardsCompatConstructor() {
        AgentResult result = new AgentResult("final answer", "{\"out\":true}", 120L);
        assertEquals("final answer", result.finalAnswer());
        assertEquals("{\"out\":true}", result.structuredOutput());
        assertTrue(result.hasStructuredOutput());
        assertEquals(120, result.metrics().durationMs());
        assertEquals(StopReason.COMPLETED, result.stopReason());
        assertNull(result.sessionId());
    }

    @Test
    void backwardsCompatConstructorNullStructuredOutput() {
        AgentResult result = new AgentResult("answer", null, 50L);
        assertFalse(result.hasStructuredOutput());
    }

    @Test
    void hasStructuredOutputBlank() {
        ExecutionMetrics metrics = new ExecutionMetrics(0, 0, 0, 0);
        AgentResult result = new AgentResult("s", "a", List.of(), metrics, StopReason.COMPLETED, "   ");
        assertFalse(result.hasStructuredOutput());
    }

    @Test
    void generatedMessagesPreserved() {
        Message msg = Message.user("test");
        ExecutionMetrics metrics = new ExecutionMetrics(0, 0, 0, 0);
        AgentResult result = new AgentResult("s", "a", List.of(msg), metrics, StopReason.COMPLETED);
        assertEquals(1, result.generatedMessages().size());
        assertEquals("test", result.generatedMessages().get(0).content());
    }
}
