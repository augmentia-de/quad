package de.augmentia.quad.core.workflow.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuadWorkflowV2SchemaValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Minimal but structurally complete v2 document (agent + decision + end). */
    private static final String VALID_V2 = """
        {
          "format": "quad.workflow",
          "schemaVersion": 2,
          "exportedAt": "2026-10-09T12:00:00Z",
          "contentHash": null,
          "mainWorkflow": {
            "id": "wf-main",
            "name": "Invoice review",
            "version": "1.0.0",
            "status": "published",
            "initialTask": "review",
            "nodes": [
              {"id": "n1", "type": "agent", "title": "Extract",
               "agent": {"ref": "agents/extractor@1.3.0", "contentHash": "sha256:abc"},
               "output": {"as": "$context.extract"}},
              {"id": "d1", "type": "decision", "dependsOn": ["n1"],
               "branches": [
                 {"branchId": "auto", "condition": "${ $context.extract.confidence > 0.9 }", "target": "end"}
               ],
               "defaultTarget": "end"},
              {"id": "end", "type": "end", "dependsOn": ["d1"], "output": null}
            ]
          },
          "agents": [
            {"id": "agents/extractor@1.3.0", "name": "Extractor", "model": "gpt-4o",
             "tools": ["read_file"], "active": true}
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

    private ObjectNode node(JsonNode doc, int index) {
        return (ObjectNode) doc.path("mainWorkflow").path("nodes").get(index);
    }

    @Test
    void v2SchemaResourceIsWellFormed() {
        JsonNode schema = QuadWorkflowSchemaValidator.loadSchema(QuadWorkflowSchemaValidator.SCHEMA_V2_RESOURCE);
        assertThat(schema.path("$id").asText()).contains("quad.workflow.v2.schema.json");
        assertThat(schema.path("properties").path("schemaVersion").path("const").asInt()).isEqualTo(2);
        assertThat(schema.path("$defs").has("nodeAgent")).isTrue();
        assertThat(schema.path("$defs").path("agentRef").path("required"))
            .extracting(JsonNode::asText)
            .containsExactlyInAnyOrder("ref", "contentHash");
    }

    @Test
    void validV2Document_passes() {
        assertThat(QuadWorkflowSchemaValidator.validateV2(parse(VALID_V2))).isEmpty();
    }

    @Test
    void versionHelperRecognisesV2() {
        assertThat(QuadWorkflowSchemaValidator.isSchemaVersionSupportedV2(parse(VALID_V2))).isTrue();
    }

    @Test
    void missingFormat_fails() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        ((ObjectNode) doc).remove("format");
        assertThat(QuadWorkflowSchemaValidator.validateV2(doc))
            .isNotEmpty()
            .anyMatch(i -> i.contains("required property 'format'"));
    }

    @Test
    void v1VersionAgainstV2_fails() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        ((ObjectNode) doc).put("schemaVersion", 1);
        assertThat(QuadWorkflowSchemaValidator.validateV2(doc))
            .isNotEmpty()
            .anyMatch(i -> i.contains("expected constant 2"));
    }

    @Test
    void unknownNodeType_fails() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        node(doc, 0).put("type", "bogus");
        assertThat(QuadWorkflowSchemaValidator.validateV2(doc)).isNotEmpty();
    }

    @Test
    void agentNodeWithoutAgent_fails() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        node(doc, 0).remove("agent");
        assertThat(QuadWorkflowSchemaValidator.validateV2(doc))
            .isNotEmpty()
            .anyMatch(i -> i.contains("missing required property 'agent'"));
    }

    @Test
    void agentRefWithoutContentHash_fails() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        ((ObjectNode) node(doc, 0).path("agent")).remove("contentHash");
        assertThat(QuadWorkflowSchemaValidator.validateV2(doc))
            .isNotEmpty()
            .anyMatch(i -> i.contains("contentHash"));
    }

    @Test
    void nodeWithAdditionalProperty_fails() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        node(doc, 0).put("bogus", true);
        assertThat(QuadWorkflowSchemaValidator.validateV2(doc))
            .isNotEmpty()
            .anyMatch(i -> i.contains("additional property 'bogus' is not allowed"));
    }

    @Test
    void authoringOverrides_unknownProperty_fails() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        node(doc, 0).putObject("overrides").put("bogus", 1);
        assertThat(QuadWorkflowSchemaValidator.validateV2(doc))
            .isNotEmpty()
            .anyMatch(i -> i.contains("additional property 'bogus' is not allowed"));
    }

    @Test
    void authoringOverrides_allowedProperties_pass() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        node(doc, 0).putObject("overrides")
            .put("model", "gpt-4o-mini")
            .put("temperature", 0.3)
            .put("systemPrompt", "be terse");
        assertThat(QuadWorkflowSchemaValidator.validateV2(doc)).isEmpty();
    }

    @Test
    void validationIsDeterministic() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        node(doc, 0).put("type", "bogus");
        List<String> first = QuadWorkflowSchemaValidator.validateV2(doc);
        List<String> second = QuadWorkflowSchemaValidator.validateV2(doc);
        assertThat(first).containsExactlyElementsOf(second);
    }

    @Test
    void v1ValidatorStillAcceptsV1() {
        // regression: the v1 entry point must keep validating against the v1 schema
        String v1 = """
            {"format":"quad.workflow","schemaVersion":1,
             "workflows":[{"id":"w","name":"W","nodes":[{"id":"n","type":"agent"}],"edges":[]}]}
            """;
        assertThat(QuadWorkflowSchemaValidator.validate(parse(v1))).isEmpty();
        assertThat(QuadWorkflowSchemaValidator.validateV2(parse(v1))).isNotEmpty();
    }
}
