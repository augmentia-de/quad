package de.augmentia.quad.core.workflow.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuadWorkflowUpcasterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String V1 = """
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
                {"id": "n1", "type": "agent", "title": "Research", "config": {"agentId": "agent-a"}},
                {"id": "c1", "type": "conditional", "title": "Check"},
                {"id": "l1", "type": "loop", "title": "Retry",
                 "config": {"maxIterations": 3, "exitCondition": "${ $context.done }"}},
                {"id": "n2", "type": "agent", "title": "Report", "config": {"agentId": "agent-a"}},
                {"id": "end", "type": "end", "title": "Done"}
              ],
              "edges": [
                {"id": "e1", "source": "n1", "target": "c1"},
                {"id": "e2", "source": "c1", "target": "l1", "condition": "${ $context.ok }"},
                {"id": "e3", "source": "c1", "target": "n2"},
                {"id": "e4", "source": "l1", "target": "n2"},
                {"id": "e5", "source": "n2", "target": "end"}
              ]
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
             "tools": ["readFile"], "jsonInput": true, "active": true}
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
    void upcastProducesSchemaValidV2() {
        JsonNode v2 = new QuadWorkflowUpcaster().upcast(V1);
        assertThat(QuadWorkflowSchemaValidator.validateV2(v2)).isEmpty();
    }

    @Test
    void upcastSplitsMainAndSubWorkflowsAndPortsAgents() {
        JsonNode v2 = new QuadWorkflowUpcaster().upcast(V1);
        assertThat(v2.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(v2.path("mainWorkflow").path("id").asText()).isEqualTo("workflow-main");
        assertThat(v2.path("subWorkflows")).hasSize(1);
        assertThat(v2.path("agents")).hasSize(1);
        assertThat(v2.path("agents").get(0).path("jsonOutput").asBoolean()).isTrue();
    }

    @Test
    void upcastMapsNodeTypesAndDerivesEdges() {
        JsonNode nodes = new QuadWorkflowUpcaster().upcast(V1).path("mainWorkflow").path("nodes");
        assertThat(nodes.get(0).path("type").asText()).isEqualTo("agent");
        assertThat(nodes.get(1).path("type").asText()).isEqualTo("decision");
        assertThat(nodes.get(2).path("type").asText()).isEqualTo("loop");
        assertThat(nodes.get(3).path("type").asText()).isEqualTo("agent");
        assertThat(nodes.get(4).path("type").asText()).isEqualTo("end");

        // dependsOn derived from incoming edges: n2 is fed by both c1 and l1
        assertThat(nodes.get(3).path("dependsOn"))
            .extracting(JsonNode::asText)
            .containsExactlyInAnyOrder("c1", "l1");

        // decision: both outgoing edges become branches, the condition-less one is default
        JsonNode decision = nodes.get(1);
        assertThat(decision.path("branches")).hasSize(2);
        assertThat(decision.path("defaultTarget").asText()).isEqualTo("n2");

        // loop carries maxIterations/exitCondition
        assertThat(nodes.get(2).path("maxIterations").asInt()).isEqualTo(3);
        assertThat(nodes.get(2).path("exitCondition").asText()).contains("done");
    }

    @Test
    void upcastMapsNestedWorkflowToSubworkflow() {
        String v1 = """
            {"format":"quad.workflow","schemaVersion":1,
             "workflows":[{"id":"w","name":"W",
               "nodes":[
                 {"id":"a","type":"agent","config":{"agentId":"x"}},
                 {"id":"sub","type":"nested-workflow","config":{"workflowId":"child"}}
               ],
               "edges":[{"id":"e","source":"a","target":"sub"}]}]}
            """;
        JsonNode nodes = new QuadWorkflowUpcaster().upcast(v1).path("mainWorkflow").path("nodes");
        assertThat(nodes.get(1).path("type").asText()).isEqualTo("subworkflow");
        assertThat(nodes.get(1).path("workflowRef").asText()).isEqualTo("child");
    }

    @Test
    void subworkflowWithoutWorkflowId_failsClosed() {
        String v1 = """
            {"format":"quad.workflow","schemaVersion":1,
             "workflows":[{"id":"w","name":"W",
               "nodes":[{"id":"sub","type":"subflow","config":{}}],"edges":[]}]}
            """;
        assertThatThrownBy(() -> new QuadWorkflowUpcaster().upcast(v1))
            .isInstanceOf(QuadWorkflowConversionException.class);
    }

    @Test
    void publishResolvesAgentRefsAndFillsHashes() {
        QuadWorkflowUpcaster upcaster = new QuadWorkflowUpcaster();
        JsonNode draft = upcaster.upcast(V1);
        JsonNode agent = draft.path("agents").get(0);

        QuadWorkflowPublishCompiler.AgentResolver resolver =
            ref -> "agent-a".equals(ref) ? Optional.of(agent) : Optional.empty();

        JsonNode published = new QuadWorkflowPublishCompiler().publish(draft, resolver);

        assertThat(QuadWorkflowSchemaValidator.validateV2(published)).isEmpty();
        assertThat(published.path("contentHash").asText()).startsWith("sha256:");
        assertThat(published.path("mainWorkflow").path("status").asText()).isEqualTo("published");
        String contentHash = published.path("mainWorkflow").path("nodes").get(0)
            .path("agent").path("contentHash").asText();
        assertThat(contentHash).startsWith("sha256:").doesNotContain("unresolved");
    }

    @Test
    void publishFailsClosedOnUnresolvedAgentRef() {
        JsonNode draft = new QuadWorkflowUpcaster().upcast(V1);
        QuadWorkflowPublishCompiler compiler = new QuadWorkflowPublishCompiler();
        assertThatThrownBy(() -> compiler.publish(draft, ref -> Optional.empty()))
            .isInstanceOf(QuadWorkflowPublishException.class)
            .hasMessageContaining("agent-a");
    }

    @Test
    void publishIsDeterministic() {
        JsonNode draft = new QuadWorkflowUpcaster().upcast(V1);
        JsonNode agent = draft.path("agents").get(0);
        QuadWorkflowPublishCompiler compiler = new QuadWorkflowPublishCompiler();
        QuadWorkflowPublishCompiler.AgentResolver resolver =
            ref -> "agent-a".equals(ref) ? Optional.of(agent) : Optional.empty();
        assertThat(compiler.publish(draft, resolver).path("contentHash").asText())
            .isEqualTo(compiler.publish(draft, resolver).path("contentHash").asText());
    }
}
