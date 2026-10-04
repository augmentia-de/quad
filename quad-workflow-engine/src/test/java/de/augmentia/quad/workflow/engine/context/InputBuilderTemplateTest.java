package de.augmentia.quad.workflow.engine.context;

import de.augmentia.quad.contract.ApiDtos;
import de.augmentia.quad.workflow.engine.port.WorkflowDefinitionPort;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Template rendering, {@code inputMappings} and {@code previousOutput} in {@link InputBuilder}.
 *
 * <p>Covers B5 (node-level mappings with scopes), B6 (unresolved placeholders) and B7
 * ({@code previousOutput}). The second half of B1 — the duplicate rendering through
 * {@code PromptAssembler} — lives in {@code PromptAssemblerTemplateTest}, because it only becomes
 * visible at the agent.
 */
class InputBuilderTemplateTest {

    private final WorkflowDefinitionPort noDefinitions = id -> (ApiDtos.WorkflowDef) null;
    private final InputBuilder builder = new InputBuilder(noDefinitions);

    private Map<String, Object> node(String id) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", "agent");
        return n;
    }

    private Map<String, Object> nodeWithMappings(String id, Object... mappings) {
        Map<String, Object> n = node(id);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("inputMappings", List.of(mappings));
        n.put("config", config);
        return n;
    }

    private Map<String, Object> mapping(String source, String scope) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        if (scope != null) m.put("scope", scope);
        return m;
    }

    private Map<String, Object> edge(String source, String target) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("source", source);
        e.put("target", target);
        return e;
    }

    /** Stands in for the run context's history. */
    private static InputBuilder.HistoryProvider history(Map<String, List<String>> byKey) {
        return (nodeId, scope) -> byKey.getOrDefault(nodeId + "@" + scope, List.of());
    }

    // ── B7: previousOutput ───────────────────────────────────────────────────

    @Test
    void previousOutputIsTheLastPredecessorOutput() {
        var designer = node("designer");
        var edges = List.of(edge("architect", "designer"));
        var outputs = Map.of("architect", "ARCHITEKTEN-PLAN-XYZ");

        var vars = builder.buildTemplateVars(designer, edges, outputs, "USER-REQUEST-BAU-MIR-X");

        assertThat(vars.get("previousOutput"))
                .as("TODO2 B7 fixed: initialPrompt is inserted after the loop, therefore "
                        + "lastKey was always initialPrompt and previousOutput a copy of it")
                .isEqualTo("ARCHITEKTEN-PLAN-XYZ");
    }

    @Test
    void previousOutputFallsBackToInitialDataWithoutPredecessors() {
        var vars = builder.buildTemplateVars(node("solo"), List.of(), Map.of(), "ONLY-INPUT");

        assertThat(vars.get("previousOutput")).isEqualTo("ONLY-INPUT");
    }

    @Test
    void templateVarsContainPredecessorOutputUnderDottedKey() {
        var vars = builder.buildTemplateVars(node("designer"), List.of(edge("architect", "designer")),
                Map.of("architect", "PLAN"), "REQ");

        assertThat(vars.get("architect.output")).isEqualTo("PLAN");
        assertThat(vars.get("initialPrompt")).isEqualTo("REQ");
    }

    // ── B6: unresolvable placeholders ────────────────────────────────────────

    @Test
    void renderTemplateReplacesResolvablePlaceholders() {
        var rendered = builder.renderTemplate("Plan: {{architect.output}}",
                Map.of("architect.output", "PLAN-A"));

        assertThat(rendered).isEqualTo("Plan: PLAN-A");
    }

    @Test
    void renderTemplateReportsUnresolvedPlaceholders() {
        // The placeholder survives so the gap stays visible, but the render logs it. Nothing is
        // silently dropped and no wrong value is substituted.
        var rendered = builder.renderTemplate(
                "Plan: {{architect.output}}\nFeedback: {{reviewer.output@previousIteration}}",
                Map.of("architect.output", "PLAN-A"));

        assertThat(rendered)
                .as("TODO2 B6 fixed: the renderer logs unresolved placeholders; "
                        + "strict turns that into an error")
                .contains("PLAN-A")
                .contains("{{reviewer.output@previousIteration}}");
    }

    @Test
    void renderTemplateStrictThrowsOnUnresolvedPlaceholders() {
        assertThatThrownBy(() -> builder.renderTemplateStrict(
                "Feedback: {{reviewer.output@previousIteration}}", Map.of()))
                .as("TODO2 B6: the strict variant names placeholders and available keys")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewer.output@previousIteration");
    }

    // ── B5: inputMappings ────────────────────────────────────────────────────

    @Test
    void inputMappingResolvesFromTheHistory() {
        var designer = nodeWithMappings("designer", mapping("reviewer", "previousIteration"));
        builder.setHistory(history(Map.of(
                "reviewer@previousIteration", List.of("CRITICAL ERRORS: tool missing"))));

        var in = builder.buildNodeInput(designer, List.of(), Map.of(), null, "REQ", "REQ");

        assertThat(in)
                .as("TODO2 B5 fixed: inputMappings read the output history, not only edges")
                .contains("[reviewer@previousIteration]: CRITICAL ERRORS: tool missing");
    }

    @Test
    void inputMappingWithScopeAllJoinsEveryIteration() {
        var designer = nodeWithMappings("designer", mapping("reviewer", "all"));
        builder.setHistory(history(Map.of(
                "reviewer@all", List.of("runde-1", "runde-2", "runde-3"))));

        var in = builder.buildNodeInput(designer, List.of(), Map.of(), null, "REQ", "REQ");

        assertThat(in)
                .contains("runde-1")
                .contains("runde-2")
                .contains("runde-3");
    }

    @Test
    void inputMappingContributesATemplateVariable() {
        var designer = nodeWithMappings("designer", mapping("reviewer", "previousIteration"));
        builder.setHistory(history(Map.of(
                "reviewer@previousIteration", List.of("feedback-text"))));

        var vars = builder.buildTemplateVars(designer, List.of(), Map.of(), "REQ");

        assertThat(vars.get("reviewer.output@previousIteration"))
                .as("TODO2 B5: mappings are also reachable as a template variable")
                .isEqualTo("feedback-text");
    }

    @Test
    void unresolvedInputMappingProducesNoEmptySection() {
        var designer = nodeWithMappings("designer", mapping("reviewer", "previousIteration"));
        builder.setHistory(history(Map.of()));

        var in = builder.buildNodeInput(designer, List.of(), Map.of(), null, "REQ", "REQ");

        assertThat(in)
                .as("an empty section would suggest an empty source to the model")
                .doesNotContain("[reviewer@previousIteration]");
    }

    @Test
    void inputMappingsAreAdditiveToEdgeMappings() {
        var designer = nodeWithMappings("designer", mapping("reviewer", "last"));
        builder.setHistory(history(Map.of("reviewer@last", List.of("R"))));

        var in = builder.buildNodeInput(designer, List.of(edge("architect", "designer")),
                Map.of("architect", "PLAN-A"), null, "REQ", "REQ");

        assertThat(in).contains("[architect]: PLAN-A").contains("[reviewer@last]: R");
    }

    @Test
    void inputMappingFormatJsonUsesTheJsonPathResolver() {
        Map<String, Object> m = new LinkedHashMap<>(mapping("reviewer", "last"));
        m.put("format", "json");
        m.put("paths", List.of("kritischeFehler"));

        var designer = nodeWithMappings("designer", m);
        builder.setHistory(history(Map.of(
                "reviewer@last", List.of("{\"kritischeFehler\":[\"Tool BOOKMARK missing\"]}"))));

        var in = builder.buildNodeInput(designer, List.of(), Map.of(), null, "REQ", "REQ");

        assertThat(in)
                .as("TODO2 B5: format=json goes through the existing JsonPathResolver")
                .contains("(JSON, path=kritischeFehler):")
                .contains("Tool BOOKMARK missing");
    }

    // ── Regression: the existing edge mapping stays untouched ───────────────

    @Test
    void edgeMappedInputStillWorks() {
        var outputs = Map.of("architect", "PLAN-A");
        var in = builder.buildNodeInput(node("designer"), List.of(edge("architect", "designer")),
                outputs, null, "REQ", "REQ");

        assertThat(in).contains("[architect]: PLAN-A");
    }

    @Test
    void noPredecessorOutputFallsBackToInitialData() {
        var in = builder.buildNodeInput(node("solo"), List.of(), Map.of(), null, "REQ", "REQ");

        assertThat(in).contains("[Initial Data]: REQ");
    }

    @Test
    void buildNodeInputWithoutHistoryProviderKeepsWorking() {
        // Callers that do not set a provider must not NPE.
        var plain = new InputBuilder(noDefinitions);
        var in = plain.buildNodeInput(node("designer"), List.of(edge("architect", "designer")),
                Map.of("architect", "PLAN-A"), null, "REQ", "REQ");

        assertThat(in).contains("[architect]: PLAN-A");
    }
}