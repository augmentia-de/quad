package de.augmentia.quad.core.tool;

import de.augmentia.quad.core.session.AgentSessionState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fehler-Semantik von {@link ToolActivatorTool} (TODO2 A5).
 *
 * <p>Der Kern: das Tool verpackt Fehler als {@code ToolResult.success}. Das Modell kann Fehler
 * nicht von Bestätigungen unterscheiden und wiederholt denselben Aufruf. strands macht es richtig
 * ({@code tools/ToolActivator.java:77, 82, 89} nutzen {@code ToolResult.error}).
 */
class ToolActivatorToolErrorSemanticsTest {

    private static ToolActivatorTool toolWith(Map<String, ToolMethod> available) {
        var reg = new QuadToolRegistry(new ToolArgsMapper(new com.fasterxml.jackson.databind.ObjectMapper()));
        var t = new ToolActivatorTool(reg);
        available.forEach(t::registerAvailable);
        return t;
    }

    private static ToolMethod stub(String name, String result) {
        return new ToolMethod() {
            @Override
            public dev.langchain4j.agent.tool.ToolSpecification spec() {
                return dev.langchain4j.agent.tool.ToolSpecification.builder()
                        .name(name).description("stub").build();
            }

            @Override
            public ToolResult execute(String jsonArguments, AgentSessionState state) {
                return ToolResult.success(result);
            }
        };
    }
    @Test
    void missingParameters_areReportedAsError() throws Exception {
        var t = toolWith(new LinkedHashMap<>());
        var result = t.execute("{}", AgentSessionState.create("s"));

        assertThat(result.isError())
                .as("TODO2 A5 behoben: fehlende Parameter muessen ein Fehler sein, kein Erfolg")
                .isTrue();
    }
    @Test
    void unknownTool_isReportedAsError() throws Exception {
        var t = toolWith(Map.of("known", stub("known", "ok")));
        var result = t.execute("{\"action\":\"add\",\"tool\":\"unknown\"}", AgentSessionState.create("s"));

        assertThat(result.isError())
                .as("TODO2 A5 behoben: unbekanntes Tool muessen ein Fehler sein")
                .isTrue();
    }
    @Test
    void unknownAction_isReportedAsError() throws Exception {
        var t = toolWith(Map.of("known", stub("known", "ok")));
        var result = t.execute("{\"action\":\"sideways\",\"tool\":\"known\"}", AgentSessionState.create("s"));

        assertThat(result.isError())
                .as("TODO2 A5 behoben: unbekannte Aktion muessen ein Fehler sein")
                .isTrue();
    }
    @Test
    void specDeclaresItsParameters() {
        var t = toolWith(Map.of());
        var spec = t.spec();

        assertThat(spec.parameters())
                .as("TODO2 A5 behoben: der Spec deklariert action und tool")
                .isNotNull();
    }

    // ── Erfolgsfaelle: die duerfen nicht brechen ─────────────────────────────

    @Test
    void regression_addAndRemoveAreSuccessfulAndIdempotent() throws Exception {
        var reg = new QuadToolRegistry(new ToolArgsMapper(new com.fasterxml.jackson.databind.ObjectMapper()));
        var t = new ToolActivatorTool(reg);
        t.registerAvailable("beta", stub("beta", "B"));

        var state = AgentSessionState.create("s");

        var added = t.execute("{\"action\":\"add\",\"tool\":\"beta\"}", state);
        assertThat(added.isError()).isFalse();
        assertThat(reg.get("beta")).as("add registriert das Tool").isNotNull();

        var removed = t.execute("{\"action\":\"remove\",\"tool\":\"beta\"}", state);
        assertThat(removed.isError()).isFalse();
        assertThat(reg.get("beta")).as("remove entfernt das Tool").isNull();
    }
}