package de.augmentia.quad.quarkus.workflow.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Pure utility for resolving a JSON path within a node output.
 * Supports {@code /items/0/name}, {@code items.0.name} and {@code items[0].name}.
 */
public final class JsonPathResolver {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonPathResolver() {
    }

    /** Resolution of a subtree/state path in the JSON output of source nodes (EdgeInputMapping.path). */
    public static String resolve(String output, String path) {
        String normalized = path.trim();
        if (normalized.isEmpty()) return output;
        try {
            JsonNode root = JSON.readTree(output);
            JsonNode node;
            if (normalized.startsWith("/")) {
                node = root.at(normalized);
            } else {
                node = root;
                for (String token : pathTokens(normalized)) {
                    if (node == null || node.isMissingNode()) break;
                    if (node.isArray()) {
                        try {
                            node = node.get(Integer.parseInt(token));
                        } catch (NumberFormatException e) {
                            node = null;
                        }
                    } else {
                        node = node.get(token);
                    }
                }
            }
            if (node == null || node.isMissingNode()) {
                return "[json: path '" + path + "' not resolved]";
            }
            return node.isValueNode() ? node.asText() : node.toString();
        } catch (Exception e) {
            return "[json: path '" + path + "' not resolved on non-JSON output]";
        }
    }

    /** "items[0].name.foo", "items.0.name" and "/items/0/name" → [items, 0, name, foo]. */
    private static List<String> pathTokens(String path) {
        String p = path.replace("[", ".").replace("]", "");
        return java.util.Arrays.stream(p.split("[./]")).filter(s -> !s.isEmpty()).toList();
    }
}