package de.augmentia.quad.quarkus.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the LLM-output handling of {@link WorkflowGeneratorService}.
 *
 * <p>Everything here is a pure function over the planner's JSON reply — no LLM, no
 * CDI, no database. That is deliberate: this is the code that breaks in production
 * (the planner returns fenced JSON, dangling dependencies or cycles), and it was
 * exactly the part that had no coverage at all before.
 */
class WorkflowGeneratorParsingTest {

    private final WorkflowGeneratorService svc = new WorkflowGeneratorService();

    // ── stripFences: normalises whatever the model wraps the JSON in ──────────

    @Nested
    @DisplayName("stripFences")
    class StripFences {

        @Test
        @DisplayName("removes a plain ``` fence")
        void plainFence() {
            assertEquals("{\"a\":1}", svc.stripFences("```\n{\"a\":1}\n```"));
        }

        @Test
        @DisplayName("removes a ```json fence")
        void jsonFence() {
            assertEquals("{\"a\":1}", svc.stripFences("```json\n{\"a\":1}\n```"));
        }

        @Test
        @DisplayName("removes a bare 'json' token in front of the object")
        void bareJsonToken() {
            // Observed with DeepSeek-compatible models: "json\n{...}" without backticks.
            assertEquals("{\"a\":1}", svc.stripFences("json\n{\"a\":1}"));
        }

        @Test
        @DisplayName("leaves unwrapped JSON untouched")
        void unwrapped() {
            assertEquals("{\"a\":1}", svc.stripFences("  {\"a\":1}  "));
        }

        @Test
        @DisplayName("maps null to empty string")
        void nullInput() {
            assertEquals("", svc.stripFences(null));
        }

        @Test
        @DisplayName("cuts prose in front of the object (reasoning models do this)")
        void proseBeforeObject() {
            // Measured with space-bunny-free: the planner prefixed a sentence before the JSON.
            assertEquals("{\"a\":1}", svc.stripFences(
                "No problem, here is the workflow:\n```json\n{\"a\":1}\n```\nLet me know if you need changes."));
        }

        @Test
        @DisplayName("cuts trailing prose after the object")
        void proseAfterObject() {
            assertEquals("{\"a\":1}", svc.stripFences(
                "{\"a\":1}\n\nI chose maxIterations 1."));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "not json at all"})
        @DisplayName("does not throw on degenerate input")
        void degenerate(String raw) {
            assertNotNull(svc.stripFences(raw));
        }
    }

    // ── parseSteps: planner JSON -> GeneratedStep list ───────────────────────

    @Nested
    @DisplayName("parseSteps")
    class ParseSteps {

        private static final String MINIMAL = """
            {"workflowName":"Research","description":"d","steps":[
              {"agentRole":"A","instruction":"do a"}]}""";

        @Test
        @DisplayName("reads every field of a step")
        void readsAllFields() {
            var r = svc.parseSteps("""
                {"workflowName":"W","description":"D","steps":[
                  {"agentRole":"SQL-Expert","instruction":"query","tools":["read_file"],
                   "dependsOn":[],"timeoutSeconds":300,"systemPrompt":"be precise",
                   "data":{"output":{"type":"object","properties":{"x":{"type":"string"}}}}}]}""");

            assertEquals("W", r.workflowName());
            assertEquals("D", r.description());
            assertEquals(1, r.steps().size());

            var s = r.steps().get(0);
            assertEquals("SQL-Expert", s.agentRole());
            assertEquals("query", s.instruction());
            assertEquals(List.of("read_file"), s.tools());
            assertEquals(List.of(), s.dependsOn());
            assertEquals(300, s.timeoutSeconds());
            assertEquals("be precise", s.systemPrompt());
            assertNotNull(s.data());
            assertTrue(s.data().containsKey("output"));
        }

        @Test
        @DisplayName("defaults timeoutSeconds to 120 when absent")
        void defaultTimeout() {
            assertEquals(120, svc.parseSteps(MINIMAL).steps().get(0).timeoutSeconds());
        }

        @Test
        @DisplayName("accepts a step without the optional fields")
        void minimalStep() {
            assertEquals("do a", svc.parseSteps(MINIMAL).steps().get(0).instruction());
        }

        @Test
        @DisplayName("reads declared dependencies")
        void readsDependencies() {
            var r = svc.parseSteps("""
                {"steps":[
                  {"agentRole":"A","dependsOn":[]},
                  {"agentRole":"B","dependsOn":["A"]}]}""");
            assertEquals(List.of("A"), r.steps().get(1).dependsOn());
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "not json",
            "{}",
            "{\"steps\":\"nope\"}",
            "{\"steps\":[]}",
            "{\"steps\":[{\"instruction\":\"no role\"}]}",
            "{\"steps\":[{\"agentRole\":\"  \"}]}",
            "{\"steps\":[{\"agentRole\":\"A\"},{\"agentRole\":\"A\"}]}"
        })
        @DisplayName("rejects malformed planner output")
        void rejectsMalformed(String json) {
            assertThrows(IllegalArgumentException.class, () -> svc.parseSteps(json));
        }

        @Test
        @DisplayName("rejects a dependency on a step that does not exist")
        void rejectsDanglingDependency() {
            var e = assertThrows(IllegalArgumentException.class, () -> svc.parseSteps("""
                {"steps":[
                  {"agentRole":"A","dependsOn":[]},
                  {"agentRole":"B","dependsOn":["Ghost"]}]}"""));
            assertTrue(e.getMessage().contains("non-existent step 'Ghost'"), e.getMessage());
        }

        @Test
        @DisplayName("rejects a two-node cycle")
        void rejectsTwoNodeCycle() {
            var e = assertThrows(IllegalArgumentException.class, () -> svc.parseSteps("""
                {"steps":[
                  {"agentRole":"A","dependsOn":["B"]},
                  {"agentRole":"B","dependsOn":["A"]}]}"""));
            assertTrue(e.getMessage().contains("Cycle detected"), e.getMessage());
        }

        @Test
        @DisplayName("rejects a three-node cycle")
        void rejectsThreeNodeCycle() {
            assertThrows(IllegalArgumentException.class, () -> svc.parseSteps("""
                {"steps":[
                  {"agentRole":"A","dependsOn":["C"]},
                  {"agentRole":"B","dependsOn":["A"]},
                  {"agentRole":"C","dependsOn":["B"]}]}"""));
        }

        @Test
        @DisplayName("rejects a self-dependency")
        void rejectsSelfCycle() {
            assertThrows(IllegalArgumentException.class, () -> svc.parseSteps("""
                {"steps":[{"agentRole":"A","dependsOn":["A"]}]}"""));
        }

        @Test
        @DisplayName("accepts a diamond: two branches merging into one step")
        void acceptsDiamond() {
            var r = svc.parseSteps("""
                {"steps":[
                  {"agentRole":"Root","dependsOn":[]},
                  {"agentRole":"Left","dependsOn":["Root"]},
                  {"agentRole":"Right","dependsOn":["Root"]},
                  {"agentRole":"Join","dependsOn":["Left","Right"]}]}""");
            assertEquals(4, r.steps().size());
        }

        @Test
        @DisplayName("accepts independent parallel steps with no dependencies")
        void acceptsParallel() {
            var r = svc.parseSteps("""
                {"steps":[
                  {"agentRole":"A","dependsOn":[]},
                  {"agentRole":"B","dependsOn":[]},
                  {"agentRole":"C","dependsOn":[]}]}""");
            assertEquals(3, r.steps().size());
        }
    }

    // ── validateSteps: reachable directly for focused graph tests ────────────

    @Nested
    @DisplayName("validateSteps")
    class ValidateSteps {

        private WorkflowGeneratorService.GeneratedStep[] arr(
                List<WorkflowGeneratorService.GeneratedStep> steps) {
            return steps.toArray(new WorkflowGeneratorService.GeneratedStep[0]);
        }

        private Map<String, WorkflowGeneratorService.GeneratedStep> byRole(
                WorkflowGeneratorService.GeneratedStep... steps) {
            var m = new LinkedHashMap<String, WorkflowGeneratorService.GeneratedStep>();
            for (var s : steps) {
                m.put(s.agentRole(), s);
            }
            return m;
        }

        private WorkflowGeneratorService.GeneratedStep step(String role, String... deps) {
            return new WorkflowGeneratorService.GeneratedStep(
                role, "i", List.of(), List.of(deps), 120, null, null);
        }

        @Test
        @DisplayName("passes for a linear chain")
        void linearChain() {
            var steps = List.of(step("A"), step("B", "A"), step("C", "B"));
            svc.validateSteps(steps, byRole(arr(steps)));
        }

        @Test
        @DisplayName("reports the offending step on a dangling dependency")
        void dangling() {
            var steps = List.of(step("A", "Nope"));
            var e = assertThrows(IllegalArgumentException.class,
                () -> svc.validateSteps(steps, byRole(arr(steps))));
            assertTrue(e.getMessage().contains("Step 'A'"), e.getMessage());
        }

        @Test
        @DisplayName("a node depending on itself counts as a cycle")
        void selfCycle() {
            var steps = List.of(step("A", "A"));
            var e = assertThrows(IllegalArgumentException.class,
                () -> svc.validateSteps(steps, byRole(arr(steps))));
            assertTrue(e.getMessage().contains("Cycle detected"), e.getMessage());
        }
    }

    // ── outputSchemaOf: only a real schema is enforced on the model ──────────

    @Nested
    @DisplayName("outputSchemaOf")
    class OutputSchemaOf {

        @Test
        @DisplayName("returns null when there is no output key")
        void noOutput() {
            assertNull(svc.outputSchemaOf(Map.of()));
        }

        @Test
        @DisplayName("returns null for an empty output object")
        void emptyOutput() {
            assertNull(svc.outputSchemaOf(Map.of("output", Map.of())));
        }

        @Test
        @DisplayName("returns null when only 'type' is given (nothing to enforce)")
        void onlyType() {
            assertNull(svc.outputSchemaOf(Map.of("output", Map.of("type", "object"))));
        }

        @Test
        @DisplayName("serialises a real schema")
        void realSchema() {
            String json = svc.outputSchemaOf(Map.of("output", Map.of(
                "type", "object",
                "properties", Map.of("title", Map.of("type", "string")))));
            assertNotNull(json);
            assertTrue(json.contains("\"type\""), json);
            assertTrue(json.contains("\"properties\""), json);
            assertTrue(json.contains("title"), json);
        }

        @Test
        @DisplayName("returns null when output is not an object")
        void notAnObject() {
            assertNull(svc.outputSchemaOf(Map.of("output", "string")));
            assertNull(svc.outputSchemaOf(Map.of("other", "object")));
        }
    }
}