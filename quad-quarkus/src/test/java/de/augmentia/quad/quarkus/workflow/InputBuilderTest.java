package de.augmentia.quad.quarkus.workflow;

import de.augmentia.quad.quarkus.workflow.context.InputBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputBuilderTest {

    private final InputBuilder builder = new InputBuilder();

    private Map<String, Object> node(String id) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        return n;
    }

    private Map<String, Object> edge(String source, String target, Object input, Object inputs) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("source", source);
        e.put("target", target);
        if (input != null) e.put("input", input);
        if (inputs != null) e.put("inputs", inputs);
        return e;
    }

    private Map<String, Object> textSource(String sourceNodeId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceNodeId", sourceNodeId);
        m.put("format", "text");
        return m;
    }

    @Test
    void legacyEdgeWithoutInputFallsBackToEdgeSource() {
        Map<String, String> outputs = Map.of("a", "OUTPUT-A");
        List<Map<String, Object>> edges = List.of(edge("a", "b", null, null));
        String in = builder.buildNodeInput(node("b"), edges, outputs, null, null, null);
        assertTrue(in.contains("[a]: OUTPUT-A"));
    }

    @Test
    void multipleInputSourcesFeedAllPredecessors() {
        Map<String, String> outputs = Map.of("a", "OUTPUT-A", "c", "OUTPUT-C");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sourceNodeId", "a");
        input.put("format", "text");
        List<Map<String, Object>> edges = List.of(
            edge("a", "b", input, null),
            edge("c", "b", textSource("c"), null)
        );
        String in = builder.buildNodeInput(node("b"), edges, outputs, null, null, null);
        assertTrue(in.contains("[a]: OUTPUT-A"));
        assertTrue(in.contains("[c]: OUTPUT-C"));
    }

    @Test
    void jsonInputsListWithMultiplePathsResolvesEachSubtree() {
        Map<String, String> outputs = Map.of(
            "a", "{\"items\":[{\"name\":\"x\"},{\"name\":\"y\"}],\"total\":2}"
        );
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("sourceNodeId", "a");
        mapping.put("format", "json");
        mapping.put("paths", List.of("items[0].name", "total"));

        List<Map<String, Object>> edges = List.of(edge("a", "b", null, List.of(mapping)));
        String in = builder.buildNodeInput(node("b"), edges, outputs, null, null, null);

        assertTrue(in.contains("(JSON, path=items[0].name):"));
        assertTrue(in.contains("x"));
        assertTrue(in.contains("(JSON, path=total):"));
        assertTrue(in.contains("2"));
        assertFalse(in.contains("[a] (JSON):\n{\"items\""));
    }

    @Test
    void jsonInputsWithSinglePathFieldStillWorks() {
        Map<String, String> outputs = Map.of("a", "{\"score\":7}");
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("sourceNodeId", "a");
        mapping.put("format", "json");
        mapping.put("path", "score");

        List<Map<String, Object>> edges = List.of(edge("a", "b", mapping, null));
        String in = builder.buildNodeInput(node("b"), edges, outputs, null, null, null);

        assertTrue(in.contains("(JSON, path=score):"));
        assertTrue(in.contains("7"));
    }

    @Test
    void ignoresPathsOnNonJsonFormat() {
        Map<String, String> outputs = Map.of("a", "PLAIN");
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("sourceNodeId", "a");
        mapping.put("format", "text");
        mapping.put("paths", List.of("foo"));

        List<Map<String, Object>> edges = List.of(edge("a", "b", null, List.of(mapping)));
        String in = builder.buildNodeInput(node("b"), edges, outputs, null, null, null);

        assertTrue(in.contains("[a]: PLAIN"));
        assertFalse(in.contains("path=foo"));
    }
}
