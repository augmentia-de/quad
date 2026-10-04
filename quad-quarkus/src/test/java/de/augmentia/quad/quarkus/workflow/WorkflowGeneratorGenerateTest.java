package de.augmentia.quad.quarkus.workflow;

import de.augmentia.quad.contract.ApiDtos;
import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.quarkus.messaging.ChannelAgentFactory;
import de.augmentia.quad.quarkus.ui.PersistenceStore;
import de.augmentia.quad.quarkus.ui.SharedState;
import de.augmentia.quad.quarkus.ui.UiConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@code WorkflowGeneratorService.generate} end to end without an LLM.
 *
 * <p>The orchestrator agent is built by a stubbed {@link ChannelAgentFactory} that
 * returns a real {@link Agent} wired to a {@link StubChatModel}. Everything between
 * the model reply and the persisted workflow — prompt assembly, fence stripping,
 * parsing, validation, retry, agent creation, DAG assembly, JSON transport edges —
 * is the production code path.
 *
 * <p>This closes the gap that the retry loop had no coverage: the known production
 * flake is "the planner returns an inconsistent step reference, {@code validateSteps}
 * rejects it and all retries fail", which is exactly what {@link #recoversAfterBadJson}
 * and {@link #failsAfterMaxRetries} pin down.
 */
class WorkflowGeneratorGenerateTest {

    /** Records what the service asked to be built, and hands out one prepared agent. */
    private static final class StubFactory extends ChannelAgentFactory {
        final List<ApiDtos.AgentDefinition> requested = new ArrayList<>();
        private final Agent agent;

        StubFactory(Agent agent) {
            this.agent = agent;
        }

        @Override
        public Agent buildFromDefinition(ApiDtos.AgentDefinition def) {
            requested.add(def);
            return agent;
        }
    }

    /** Swallows the persistence writes; the service only needs putAgent to not blow up. */
    private static final class NoopStore extends PersistenceStore {
        final List<String> upserted = new ArrayList<>();

        @Override
        public void upsertAgent(ApiDtos.AgentDefinition agent) {
            upserted.add(agent.id);
        }
    }

    /** Minimal concrete agent: no tools, no hooks, LLM supplied by the test. */
    private static final class PlainAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }
    }

    private record Harness(WorkflowGeneratorService svc, StubFactory factory,
                           NoopStore store, StubChatModel model) {}

    private static Harness harness(StubChatModel model) {
        var agent = new PlainAgent();
        agent.setLlm(model);

        var factory = new StubFactory(agent);
        var store = new NoopStore();

        var state = new SharedState();
        // SharedState.db is package-private in de.augmentia.quad.quarkus.ui and this test
        // lives in the workflow package, so inject the stub reflectively rather than
        // widening the production visibility.
        try {
            var dbField = SharedState.class.getDeclaredField("db");
            dbField.setAccessible(true);
            dbField.set(state, store);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot inject stub PersistenceStore", e);
        }

        var svc = new WorkflowGeneratorService();
        svc.agentFactory = factory;
        svc.state = state;
        svc.uiConfig = UiConfig.builder().model("test-model").build();
        return new Harness(svc, factory, store, model);
    }

    private static final String VALID = """
        {"workflowName":"E-Scooter Marktanalyse","description":"research + summary",
         "steps":[
           {"agentRole":"Researcher","instruction":"Research the market","tools":[],"dependsOn":[],
            "timeoutSeconds":120,
            "data":{"output":{"type":"object","properties":{"findings":{"type":"string"}}}}},
           {"agentRole":"Summarizer","instruction":"Summarise the findings","tools":[],
            "dependsOn":["Researcher"],"timeoutSeconds":90}]}""";

    @Test
    @DisplayName("generate turns a valid planner reply into a two-step workflow")
    void generatesWorkflow() {
        var h = harness(StubChatModel.replying(VALID));

        var wf = h.svc.generate("Research the e-scooter market", "Marktanalyse");

        assertNotNull(wf);
        assertEquals("Marktanalyse", wf.name);
        assertTrue(wf.id.startsWith("workflow-dyn-"), wf.id);
        assertEquals(2, wf.nodes.size());
        assertEquals(1, wf.edges.size(), "one dependency edge expected");
        assertEquals(1, h.model.callCount(), "one LLM call for a valid reply");
    }

    @Test
    @DisplayName("a fenced reply is unwrapped before parsing")
    void stripsFences() {
        var h = harness(StubChatModel.replying("```json\n" + VALID + "\n```"));

        var wf = h.svc.generate("task", "Fenced");

        assertEquals(2, wf.nodes.size());
    }

    @Test
    @DisplayName("retries and succeeds when the first reply is unusable")
    void recoversAfterBadJson() {
        // First reply is not JSON, second is valid: the retry hint must recover the call.
        var h = harness(new StubChatModel("I cannot help with that.", VALID));

        var wf = h.svc.generate("task", "Retry");

        assertEquals(2, wf.nodes.size());
        assertEquals(2, h.model.callCount(), "expected exactly one retry");
    }

    @Test
    @DisplayName("retries and succeeds when the first reply has a dangling dependency")
    void recoversAfterValidationError() {
        String bad = """
            {"steps":[
              {"agentRole":"A","dependsOn":["Ghost"]}]}""";
        var h = harness(new StubChatModel(bad, VALID));

        var wf = h.svc.generate("task", "ValidateRetry");

        assertEquals(2, wf.nodes.size());
        assertEquals(2, h.model.callCount());
    }

    @Test
    @DisplayName("the retry prompt tells the model to return bare JSON")
    void retryPromptAsksForBareJson() {
        var h = harness(new StubChatModel("not json", VALID));

        h.svc.generate("task", "Hint");

        assertEquals(2, h.model.callCount());
        String second = StubChatModel.userText(h.model.requests().get(1));
        assertNotNull(second, "a second request was expected");
        assertTrue(second.contains("previous attempt failed"), second);
    }

    @Test
    @DisplayName("gives up with a clear message after maxRetries")
    void failsAfterMaxRetries() {
        var h = harness(new StubChatModel("nope", "still nope", "nope again"));

        var e = assertThrows(RuntimeException.class,
            () -> h.svc.generate("task", "Doomed", 3));
        assertTrue(e.getMessage().contains("Decomposition failed after 3 attempts"), e.getMessage());
        assertEquals(3, h.model.callCount());
    }

    @Test
    @DisplayName("maxRetries=1 does not retry at all")
    void singleAttempt() {
        var h = harness(new StubChatModel("nope"));

        assertThrows(RuntimeException.class, () -> h.svc.generate("task", "Once", 1));
        assertEquals(1, h.model.callCount());
    }

    @Test
    @DisplayName("one persisted agent is created per generated step")
    void createsOneAgentPerStep() {
        var h = harness(StubChatModel.replying(VALID));

        h.svc.generate("task", "Agents");

        assertEquals(2, h.store.upserted.size(),
            "expected one persisted agent per step, got " + h.store.upserted);
    }

    @Test
    @DisplayName("every node references a persisted agent id")
    void nodesReferencePersistedAgents() {
        var h = harness(StubChatModel.replying(VALID));

        var wf = h.svc.generate("task", "Refs");

        for (var n : wf.nodes) {
            if (!"agent".equals(n.get("type"))) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) n.get("config");
            String agentId = (String) config.get("agentId");
            assertNotNull(agentId, "node " + n.get("id") + " has no agentId");
            assertTrue(h.store.upserted.contains(agentId),
                "node " + n.get("id") + " references unknown agent " + agentId);
        }
    }

    @Test
    @DisplayName("a step with a schema gets jsonOutputSchema, a plain one does not")
    void schemaOnlyWhenDeclared() {
        var h = harness(StubChatModel.replying(VALID));

        var wf = h.svc.generate("task", "Schema");

        Map<String, Object> researcher = configOf(wf.nodes, "Researcher");
        assertNotNull(researcher.get("jsonOutputSchema"), "declared schema must be enforced");

        Map<String, Object> summarizer = configOf(wf.nodes, "Summarizer");
        assertFalse(summarizer.containsKey("jsonOutputSchema"),
            "a step without data schema must not get one");
    }

    @Test
    @DisplayName("a step with a predecessor gets jsonOutput and a template reference")
    void successorGetsTemplate() {
        var h = harness(StubChatModel.replying(VALID));

        var wf = h.svc.generate("task", "Template");

        Map<String, Object> first = configOf(wf.nodes, "Researcher");
        assertFalse(first.containsKey("jsonOutput"), "the root step has no predecessor");

        Map<String, Object> second = configOf(wf.nodes, "Summarizer");
        assertEquals(Boolean.TRUE, second.get("jsonOutput"));
        String tpl = (String) second.get("userMessageTemplate");
        assertNotNull(tpl, "successor must receive a follow-up template");
        assertTrue(tpl.contains("{{node-Researcher.output}}"), tpl);
    }

    @Test
    @DisplayName("the orchestrator agent is built with the Workflow-Architect system prompt")
    void orchestratorPromptIsTheArchitectPrompt() {
        var h = harness(StubChatModel.replying(VALID));

        h.svc.generate("task", "Prompt");

        assertFalse(h.factory.requested.isEmpty(), "an orchestrator agent must be built");
        var def = h.factory.requested.get(0);
        assertEquals("__generator-orchestrator__", def.id);
        assertNotNull(def.systemPrompt);
        assertTrue(def.systemPrompt.contains("Workflow Architect"), def.systemPrompt);
        assertEquals("test-model", def.model);
    }

    @Test
    @DisplayName("the user message carries the task and the tool repository")
    void userMessageCarriesTask() {
        var h = harness(StubChatModel.replying(VALID));

        h.svc.generate("Research the e-scooter market", "Payload");

        String user = h.model.firstUserMessage();
        assertNotNull(user, "the planner must receive a user message");
        assertTrue(user.contains("e-scooter"), user);
        assertTrue(user.contains("Tool Repository") || user.contains("tool"), user);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configOf(List<Map<String, Object>> nodes, String role) {
        for (var n : nodes) {
            if (role.equals(n.get("title"))) return (Map<String, Object>) n.get("config");
        }
        throw new AssertionError("no node titled " + role);
    }
}