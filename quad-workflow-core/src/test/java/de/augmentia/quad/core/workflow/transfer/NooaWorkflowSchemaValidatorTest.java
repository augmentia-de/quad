package de.augmentia.quad.core.workflow.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuadWorkflowSchemaValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String VALID_ENVELOPE = """
        {
          "format": "quad.workflow",
          "schemaVersion": 1,
          "exportedAt": "2026-09-08T10:00:00Z",
          "mainWorkflowId": "workflow-main",
          "workflows": [
            {
              "id": "workflow-main",
              "name": "Main",
              "initialTask": "do it",
              "nodes": [
                {"id": "n1", "type": "agent", "title": "Research",
                 "config": {"agentId": "agent-a", "prompt": "x"}},
                {"id": "nest", "type": "nested-workflow", "title": "Sub",
                 "config": {"workflowId": "workflow-sub"}}
              ],
              "edges": [{"id": "e1", "source": "n1", "target": "nest"}]
            },
            {
              "id": "workflow-sub",
              "name": "Sub",
              "nodes": [{"id": "s1", "type": "agent", "config": {"agentId": "agent-a"}}],
              "edges": []
            }
          ],
          "agents": [
            {"id": "agent-a", "name": "Alice", "model": "gpt-4o-mini", "maxTokens": 512,
             "tools": ["readFile"], "jsonOutput": true, "active": true}
          ]
        }
        """;

    private JsonNode parse(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void validEnvelope_passes() {
        List<String> issues = QuadWorkflowSchemaValidator.validate(parse(VALID_ENVELOPE));
        assertThat(issues).isEmpty();
    }

    @Test
    void missingFormatAndVersion_fails() {
        JsonNode doc = parse(VALID_ENVELOPE).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) doc).remove("format");
        List<String> issues = QuadWorkflowSchemaValidator.validate(doc);
        assertThat(issues).isNotEmpty()
            .anyMatch(i -> i.contains("required property 'format'"));
    }

    @Test
    void wrongSchemaVersion_fails() {
        JsonNode doc = parse(VALID_ENVELOPE);
        ((com.fasterxml.jackson.databind.node.ObjectNode) doc).put("schemaVersion", 2);
        List<String> issues = QuadWorkflowSchemaValidator.validate(doc);
        assertThat(issues).isNotEmpty()
            .anyMatch(i -> i.contains("expected constant 1"));
    }

    @Test
    void additionalEnvelopeProperty_fails() {
        JsonNode doc = parse(VALID_ENVELOPE);
        ((com.fasterxml.jackson.databind.node.ObjectNode) doc).put("unexpected", "x");
        List<String> issues = QuadWorkflowSchemaValidator.validate(doc);
        assertThat(issues).isNotEmpty()
            .anyMatch(i -> i.contains("additional property 'unexpected' is not allowed"));
    }

    @Test
    void nodeWithoutId_fails() {
        JsonNode doc = parse(VALID_ENVELOPE);
        var nodes = (com.fasterxml.jackson.databind.node.ArrayNode) doc.path("workflows").get(0).get("nodes");
        nodes.insertObject(0).put("type", "agent");
        List<String> issues = QuadWorkflowSchemaValidator.validate(doc);
        assertThat(issues).isNotEmpty()
            .anyMatch(i -> i.contains("missing required property 'id'"));
    }

    @Test
    void edgeWithoutTarget_fails() {
        JsonNode doc = parse(VALID_ENVELOPE);
        var edges = (com.fasterxml.jackson.databind.node.ArrayNode) doc.path("workflows").get(0).get("edges");
        edges.insertObject(0).put("source", "n1");
        List<String> issues = QuadWorkflowSchemaValidator.validate(doc);
        assertThat(issues).isNotEmpty()
            .anyMatch(i -> i.contains("required property 'target'"));
    }

    @Test
    void agentWithUnknownProperty_fails() {
        JsonNode doc = parse(VALID_ENVELOPE);
        ((com.fasterxml.jackson.databind.node.ObjectNode) doc.path("agents").get(0))
            .put("bogus", true);
        List<String> issues = QuadWorkflowSchemaValidator.validate(doc);
        assertThat(issues).isNotEmpty()
            .anyMatch(i -> i.contains("additional property 'bogus' is not allowed"));
    }

    @Test
    void emptyWorkflowsArray_fails() {
        JsonNode doc = parse(VALID_ENVELOPE);
        ((com.fasterxml.jackson.databind.node.ObjectNode) doc)
            .replace("workflows", MAPPER.createArrayNode());
        List<String> issues = QuadWorkflowSchemaValidator.validate(doc);
        assertThat(issues).isNotEmpty()
            .anyMatch(i -> i.contains("at least 1 items"));
    }

    @Test
    void validationIsDeterministic() {
        JsonNode doc = parse(VALID_ENVELOPE);
        ((com.fasterxml.jackson.databind.node.ObjectNode) doc).put("schemaVersion", 3);
        List<String> first = QuadWorkflowSchemaValidator.validate(doc);
        List<String> second = QuadWorkflowSchemaValidator.validate(doc);
        assertThat(first).hasSameSizeAs(second);
        assertThat(first).containsExactlyElementsOf(second);
    }

    @Test
    void lenientLocalDateTimeAccepted() {
        JsonNode doc = parse(VALID_ENVELOPE);
        ((com.fasterxml.jackson.databind.node.ObjectNode) doc.path("workflows").get(0))
            .put("createdAt", "2026-09-08T10:12:13.123");
        List<String> issues = QuadWorkflowSchemaValidator.validate(doc);
        assertThat(issues).isEmpty();
    }
}