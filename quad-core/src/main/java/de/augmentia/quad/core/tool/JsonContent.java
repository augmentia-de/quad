package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record JsonContent(JsonNode json) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static JsonContent of(ObjectNode node) {
        return new JsonContent(node);
    }

    public static JsonContent of(ArrayNode node) {
        return new JsonContent(node);
    }

    public static JsonContent from(Object pojo) {
        return new JsonContent(MAPPER.valueToTree(pojo));
    }

    public String type() {
        return "json";
    }

    @Override
    public String toString() {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (JsonProcessingException e) {
            return json.toPrettyString();
        }
    }
}
