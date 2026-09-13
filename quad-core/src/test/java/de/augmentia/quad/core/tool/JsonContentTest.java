package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonContentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void ofObjectNode() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("name", "test");
        JsonContent content = JsonContent.of(node);
        assertEquals("json", content.type());
        assertTrue(content.toString().contains("test"));
    }

    @Test
    void ofArrayNode() {
        ArrayNode node = MAPPER.createArrayNode();
        node.add(1).add(2).add(3);
        JsonContent content = JsonContent.of(node);
        assertEquals("json", content.type());
        assertTrue(content.toString().contains("1"));
        assertTrue(content.toString().contains("2"));
    }

    @Test
    void fromPojo() {
        record Simple(String name, int value) {}
        JsonContent content = JsonContent.from(new Simple("hello", 42));
        assertEquals("json", content.type());
        String str = content.toString();
        assertTrue(str.contains("hello"));
        assertTrue(str.contains("42"));
    }

    @Test
    void toStringPrettyPrints() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("a", 1);
        JsonContent content = new JsonContent(node);
        String str = content.toString();
        assertTrue(str.contains("\n"), "Should be pretty-printed with newlines");
    }
}
