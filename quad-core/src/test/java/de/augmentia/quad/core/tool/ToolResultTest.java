package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void successFactory() {
        ToolResult result = ToolResult.success("done");
        assertEquals("done", result.text());
        assertFalse(result.isError());
        assertNull(result.details());
        assertEquals(1, result.content().size());
    }

    @Test
    void successFactoryWithDetails() {
        Object details = Map.of("count", 5);
        ToolResult result = ToolResult.success("ok", details);
        assertEquals("ok", result.text());
        assertFalse(result.isError());
        assertEquals(details, result.details());
    }

    @Test
    void errorFactory() {
        ToolResult result = ToolResult.error("something broke");
        assertTrue(result.isError());
        assertTrue(result.text().contains("[ERROR]"));
        assertTrue(result.text().contains("something broke"));
        assertEquals("something broke", result.details());
    }

    @Test
    void jsonFactory() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("key", "value");
        ToolResult result = ToolResult.json(node);
        assertFalse(result.isError());
        assertTrue(result.text().contains("\"key\" : \"value\""));
    }

    @Test
    void mixedFactory() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("num", 42);
        ToolResult result = ToolResult.mixed("prefix ", node);
        assertFalse(result.isError());
        String text = result.text();
        assertTrue(text.contains("prefix "));
        assertTrue(text.contains("42"));
    }

    @Test
    void textConcatenatesMultipleBlocks() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("x", 1);
        ToolResult result = new ToolResult(List.of("A", new JsonContent(node), "B"), null);
        String text = result.text();
        assertTrue(text.startsWith("A"));
        assertTrue(text.endsWith("B"));
        assertTrue(text.contains("1"));
    }
}
