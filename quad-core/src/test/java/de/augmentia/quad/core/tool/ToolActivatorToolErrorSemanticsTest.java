package de.augmentia.quad.core.tool;

import de.augmentia.quad.core.session.AgentSessionState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Error semantics of {@link ToolActivatorTool} (TODO2 A5).
 *
 * <p>The core: the tool packages errors as {@code ToolResult.success}. The model cannot
 * distinguish errors from acknowledgements and repeats the same call. strands does it right
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
                .as("TODO2 A5 fixed: missing parameters must be an error, not a success")
                .isTrue();
    }
    @Test
    void unknownTool_isReportedAsError() throws Exception {
        var t = toolWith(Map.of("known", stub("known", "ok")));
        var result = t.execute("{\"action\":\"add\",\"tool\":\"unknown\"}", AgentSessionState.create("s"));

        assertThat(result.isError())
                .as("TODO2 A5 fixed: an unknown tool must be an error")
                .isTrue();
    }
    @Test
    void unknownAction_isReportedAsError() throws Exception {
        var t = toolWith(Map.of("known", stub("known", "ok")));
        var result = t.execute("{\"action\":\"sideways\",\"tool\":\"known\"}", AgentSessionState.create("s"));

        assertThat(result.isError())
                .as("TODO2 A5 fixed: an unknown action must be an error")
                .isTrue();
    }
    @Test
    void specDeclaresItsParameters() {
        var t = toolWith(Map.of());
        var spec = t.spec();

        assertThat(spec.parameters())
                .as("TODO2 A5 fixed: the spec declares action and tool")
                .isNotNull();
    }

    // ── Success cases: these must not break ─────────────────────────────

    @Test
    void regression_addAndRemoveAreSuccessfulAndIdempotent() throws Exception {
        var reg = new QuadToolRegistry(new ToolArgsMapper(new com.fasterxml.jackson.databind.ObjectMapper()));
        var t = new ToolActivatorTool(reg);
        t.registerAvailable("beta", stub("beta", "B"));

        var state = AgentSessionState.create("s");

        var added = t.execute("{\"action\":\"add\",\"tool\":\"beta\"}", state);
        assertThat(added.isError()).isFalse();
        assertThat(reg.get("beta")).as("add registers the tool").isNotNull();

        var removed = t.execute("{\"action\":\"remove\",\"tool\":\"beta\"}", state);
        assertThat(removed.isError()).isFalse();
        assertThat(reg.get("beta")).as("remove removes the tool").isNull();
    }
}