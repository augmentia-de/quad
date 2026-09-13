package de.augmentia.quad.core.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void userMessageFactory() {
        Message msg = Message.user("hello world");
        assertTrue(msg.isUser());
        assertFalse(msg.isSystem());
        assertFalse(msg.isAssistant());
        assertFalse(msg.isToolResult());
        assertEquals("hello world", msg.content());
        assertNotNull(msg.id());
        assertNotNull(msg.timestamp());
    }

    @Test
    void userMessageWithExplicitIdAndTimestamp() {
        Instant ts = Instant.parse("2025-01-01T00:00:00Z");
        Message msg = Message.user("id-1", ts, "text", Map.of("key", "val"));
        assertEquals("id-1", msg.id());
        assertEquals(ts, msg.timestamp());
        assertEquals("text", msg.content());
        assertEquals("val", msg.metadata().get("key"));
    }

    @Test
    void userMessageNullText() {
        Message msg = Message.user("id", Instant.now(), null, Map.of());
        assertEquals("", msg.content());
    }

    @Test
    void systemMessageFactory() {
        Message msg = Message.system("be helpful");
        assertTrue(msg.isSystem());
        assertFalse(msg.isUser());
        assertEquals("be helpful", msg.content());
    }

    @Test
    void assistantMessageFactory() {
        Message msg = Message.assistant("Sure, I can help.");
        assertTrue(msg.isAssistant());
        assertFalse(msg.isToolResult());
        assertEquals("Sure, I can help.", msg.content());
        assertFalse(msg.hasToolExecutionRequests());
        assertTrue(msg.toolExecutionRequests().isEmpty());
    }

    @Test
    void assistantMessageWithToolRequests() {
        var req = ToolExecutionRequest.builder().id("tc-1").name("search").build();
        Message msg = Message.assistant("Let me search", List.of(req));
        assertTrue(msg.hasToolExecutionRequests());
        assertEquals(1, msg.toolExecutionRequests().size());
        assertEquals("tc-1", msg.toolExecutionRequests().get(0).id());
    }

    @Test
    void toolResultMessageFactory() {
        Message msg = Message.toolResult("search result", "tc-1", "search");
        assertTrue(msg.isToolResult());
        assertEquals("search result", msg.content());
        assertEquals("tc-1", msg.toolCallId());
        assertEquals("search", msg.toolName());
    }

    @Test
    void fromChatMessage() {
        var cm = SystemMessage.from("sys");
        Message msg = Message.from(cm);
        assertTrue(msg.isSystem());
        assertEquals("sys", msg.content());
        assertNotNull(msg.id());
        assertNotNull(msg.timestamp());
    }

    @Test
    void contentExtractionForToolResultMessage() {
        Message msg = Message.toolResult("result text", "id-1", "myTool");
        assertEquals("result text", msg.content());
        assertEquals("myTool", msg.toolName());
        assertEquals("id-1", msg.toolCallId());
    }

    @Test
    void toChatMessageReturnsDelegate() {
        Message msg = Message.user("test");
        assertNotNull(msg.toChatMessage());
        assertTrue(msg.toChatMessage() instanceof UserMessage);
    }

    @Test
    void roundTripSerialization() throws Exception {
        Message original = Message.user("round-trip");
        String json = MAPPER.writeValueAsString(original);
        Message deserialized = MAPPER.readValue(json, Message.class);
        assertEquals(original.id(), deserialized.id());
        assertEquals(original.content(), deserialized.content());
        assertEquals(original.timestamp(), deserialized.timestamp());
    }

    @Test
    void roundTripSystemMessage() throws Exception {
        Message original = Message.system("sys prompt");
        String json = MAPPER.writeValueAsString(original);
        Message deserialized = MAPPER.readValue(json, Message.class);
        assertEquals("sys prompt", deserialized.content());
        assertTrue(deserialized.isSystem());
    }

    @Test
    void roundTripAssistantMessage() throws Exception {
        Message original = Message.assistant("response");
        String json = MAPPER.writeValueAsString(original);
        Message deserialized = MAPPER.readValue(json, Message.class);
        assertEquals("response", deserialized.content());
        assertTrue(deserialized.isAssistant());
    }

    @Test
    void roundTripWithMetadata() throws Exception {
        Message original = Message.user("id-1", Instant.now(), "text", Map.of("key", 42));
        String json = MAPPER.writeValueAsString(original);
        Message deserialized = MAPPER.readValue(json, Message.class);
        assertEquals(42, deserialized.metadata().get("key"));
    }
}
