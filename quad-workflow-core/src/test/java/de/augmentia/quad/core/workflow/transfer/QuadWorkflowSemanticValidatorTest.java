package de.augmentia.quad.core.workflow.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuadWorkflowSemanticValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String VALID_V2 = """
        {
          "format": "quad.workflow",
          "schemaVersion": 2,
          "mainWorkflow": {
            "id": "wf",
            "name": "W",
            "version": "1.0.0",
            "status": "published",
            "nodes": [
              {"id": "n1", "type": "agent",
               "agent": {"ref": "agents/a@1.0.0", "contentHash": "sha256:x"}},
              {"id": "d1", "type": "decision", "dependsOn": ["n1"],
               "branches": [{"branchId": "yes", "condition": "${ true }", "target": "end"}],
               "defaultTarget": "end"},
              {"id": "end", "type": "end", "dependsOn": ["d1"]}
            ]
          }
        }
        """;

    private JsonNode parse(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private ObjectNode node(JsonNode doc, int i) {
        return (ObjectNode) doc.path("mainWorkflow").path("nodes").get(i);
    }

    @Test
    void validDocument_passes() {
        assertThat(QuadWorkflowSemanticValidator.validate(parse(VALID_V2))).isEmpty();
    }

    @Test
    void duplicateNodeId_fails() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        node(doc, 2).put("id", "n1");
        assertThat(QuadWorkflowSemanticValidator.validate(doc))
            .anyMatch(e -> e.contains("duplicate node id"));
    }

    @Test
    void unknownDependency_fails() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        node(doc, 2).putArray("dependsOn").add("ghost");
        assertThat(QuadWorkflowSemanticValidator.validate(doc))
            .anyMatch(e -> e.contains("unknown reference 'ghost'"));
    }

    @Test
    void selfReference_fails() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        node(doc, 1).putArray("dependsOn").add("d1");
        assertThat(QuadWorkflowSemanticValidator.validate(doc))
            .anyMatch(e -> e.contains("self-reference"));
    }

    @Test
    void multipleEndNodes_fail() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        ArrayNode nodes = (ArrayNode) doc.path("mainWorkflow").get("nodes");
        nodes.addObject().put("id", "end2").put("type", "end");
        assertThat(QuadWorkflowSemanticValidator.validate(doc))
            .anyMatch(e -> e.contains("multiple END nodes"));
    }

    @Test
    void missingEnd_fails() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        node(doc, 2).put("type", "agent");
        assertThat(QuadWorkflowSemanticValidator.validate(doc))
            .anyMatch(e -> e.contains("no END node"));
    }

    @Test
    void cyclicDependsOn_fails() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        // n1 -> d1 already; add d1 -> n1 to close a cycle (drop the branch to end)
        node(doc, 0).putArray("dependsOn").add("d1");
        assertThat(QuadWorkflowSemanticValidator.validate(doc))
            .anyMatch(e -> e.contains("cyclic dependsOn graph"));
    }

    @Test
    void requiresHumanApprovalOnNonAgent_fails() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        node(doc, 1).put("requiresHumanApproval", true);
        assertThat(QuadWorkflowSemanticValidator.validate(doc))
            .anyMatch(e -> e.contains("requiresHumanApproval is only allowed on agent nodes"));
    }

    @Test
    void unknownWorkflowRef_failsWhenSubWorkflowsPresent() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        ArrayNode nodes = (ArrayNode) doc.path("mainWorkflow").get("nodes");
        nodes.insertObject(0).put("id", "sub").put("type", "subworkflow").put("workflowRef", "missing");
        ((ObjectNode) doc).set("subWorkflows", MAPPER.createArrayNode()
            .add(MAPPER.createObjectNode().put("id", "child").put("name", "Child")
                .put("version", "1.0.0").put("status", "published")
                .set("nodes", MAPPER.createArrayNode()
                    .add(MAPPER.createObjectNode().put("id", "c-end").put("type", "end")))));
        assertThat(QuadWorkflowSemanticValidator.validate(doc))
            .anyMatch(e -> e.contains("unknown workflowRef 'missing'"));
    }

    // ── HITL normalization ───────────────────────────────────────────────

    @Test
    void hitlGatesCoverBothMechanisms() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        ArrayNode nodes = (ArrayNode) doc.path("mainWorkflow").get("nodes");
        nodes.insertObject(0).put("id", "a1").put("type", "agent")
            .put("requiresHumanApproval", true)
            .set("agent", MAPPER.createObjectNode().put("ref", "agents/a@1.0.0").put("contentHash", "sha256:x"));
        nodes.insertObject(1).put("id", "h1").put("type", "hitl").put("assignee", "finance");
        // rewire: h1 depends on a1
        node(doc, 1).putArray("dependsOn").add("a1");

        List<QuadWorkflowHitlNormalizer.ApprovalGate> gates =
            QuadWorkflowHitlNormalizer.approvalGates(doc.path("mainWorkflow"));

        assertThat(gates).hasSize(2);
        assertThat(gates.get(0).nodeId()).isEqualTo("a1");
        assertThat(gates.get(0).mechanism())
            .isEqualTo(QuadWorkflowHitlNormalizer.Mechanism.REQUIRES_HUMAN_APPROVAL);
        assertThat(gates.get(1).nodeId()).isEqualTo("h1");
        assertThat(gates.get(1).mechanism())
            .isEqualTo(QuadWorkflowHitlNormalizer.Mechanism.HITL_NODE);
    }

    @Test
    void hitlDowncastKeepsFlagWorkflowsSemanticallyValid() {
        JsonNode doc = parse(VALID_V2).deepCopy();
        node(doc, 0).put("requiresHumanApproval", true);
        assertThat(QuadWorkflowSemanticValidator.validate(doc)).isEmpty();
        assertThat(QuadWorkflowHitlNormalizer.isApprovalGate(node(doc, 0))).isTrue();
    }
}
