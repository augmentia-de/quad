package de.augmentia.quad.core.tool;

import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.session.AgentSessionState;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bindungs- und Schema-Verhalten von {@link ReflectiveToolMethod}.
 *
 * <p>These tests document the current state, not the desired state. They deliberately fail
 * fehl, wo ein Defekt aus TODO2.md (A1–A4) besteht — damit eine Korrektur sichtbar wird, sobald
 * sie landet. Die Namen der fehlschlagenden Tests beginnen mit {@code defect}, die der
 *Regressionstests mit {@code regression}.
 */
class ReflectiveToolMethodBindingTest {

    private static ToolArgsMapper mapper() {
        return new ToolArgsMapper(new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static ReflectiveToolMethod tool(Object bean, String method, Class<?>... types) throws Exception {
        return new ReflectiveToolMethod(bean.getClass().getMethod(method, types), bean, mapper());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    /** Parameter ohne {@code @Param} — deckt den {@link Param} -Fallback auf {@code getName()} ab. */
    public static class NoParamTool {
        @Tool("no @Param anywhere")
        public String greet(String path) {
            return "content of " + path;
        }
    }

    public static class AllOptionalTool {
        @Tool("every parameter optional")
        public String listDirectory(
                @Param(value = "path", required = false) String path,
                @Param(value = "recursive", required = false) Boolean recursive,
                @Param(value = "details", required = false) Boolean details,
                @Param(value = "depth", required = false) Integer depth) {
            return "listed:" + path + "/" + recursive + "/" + details + "/" + depth;
        }
    }

    public record EditOperation(String filePath, String oldString, String newString) {}

    public static class RecordListTool {
        @Tool("multi edit")
        public String multiEdit(@Param("edits") List<EditOperation> edits) {
            return "applied " + edits.size() + " edits, first=" + edits.get(0).oldString();
        }
    }

    public static class MapTool {
        @Tool("map param")
        public String useMap(@Param("opts") Map<String, Object> opts) {
            return "keys=" + opts.keySet();
        }
    }

    public static class StringTool {
        @Tool("string param")
        public String echo(@Param("p") String p) {
            return "got:[" + p + "]";
        }
    }

    public static class PrimitiveTool {
        @Tool("int param")
        public String echo(@Param("p") int p) {
            return "got:" + p;
        }
    }

    public static class StateParamTool {
        @Tool("takes session state")
        public String useState(AgentSessionState state, @Param("p") String p) {
            return "session=" + (state == null ? "null" : state.getSessionId()) + " p=" + p;
        }
    }

    public static class NoDescriptionTool {
        @Tool
        public String bare(@Param("p") String p) {
            return p;
        }
    }

    public static class EnumTool {
        public enum Mode { FAST, SLOW }

        @Tool("enum param")
        public String useEnum(@Param("mode") Mode mode) {
            return "mode=" + mode;
        }
    }

    // ── A1: Parameternamen ohne @Param ──────────────────────────────────────
    @Test
    void parameterNameFallback_resolvesViaGetName() throws Exception {
        var tm = tool(new NoParamTool(), "greet", String.class);
        var props = tm.spec().parameters().properties();

        assertThat(props)
                .as("TODO2 A1: -parameters is set in quad-quarkus/pom.xml, therefore "
                        + "getName() den echten Namen liefern. Ohne die Flag waere es 'arg0'.")
                .containsKey("path");
    }

    @Test
    void defect_A1_withoutParametersFlag_argumentIsSilentlyLost() throws Exception {
        var tm = tool(new NoParamTool(), "greet", String.class);
        var props = tm.spec().parameters().properties();

        if (props.containsKey("arg0")) {
            // That is the defect state: the LLM sends "path", the binding looks for "arg0".
            var result = tm.execute("{\"path\":\"a.txt\"}", null);
            assertThat(result.text())
                    .as("TODO2 A1: -parameters is missing, the argument arrives as null")
                    .isEqualTo("content of null");
        } else {
            var result = tm.execute("{\"path\":\"a.txt\"}", null);
            assertThat(result.text())
                    .isEqualTo("content of a.txt");
        }
    }

    // ── A2: parameters = null bei rein optionalen Parametern ─────────────────
    @Test
    void allOptionalParameters_stillProduceAParameterSchema() throws Exception {
        var tm = tool(new AllOptionalTool(), "listDirectory", String.class, Boolean.class, Boolean.class, Integer.class);

        assertThat(tm.spec().parameters())
                .as("TODO2 A2 fixed: buildSpec always sets .parameters(), required only when non-empty")
                .isNotNull();
        assertThat(tm.spec().parameters().properties().keySet())
                .containsExactlyInAnyOrder("path", "recursive", "details", "depth");
        assertThat(tm.spec().parameters().required()).isEmpty();
    }

    @Test
    void regression_atLeastOneRequiredParameter_yieldsParameterSchema() throws Exception {
        var tm = tool(new RecordListTool(), "multiEdit", List.class);
        assertThat(tm.spec().parameters()).isNotNull();
        assertThat(tm.spec().parameters().properties().keySet()).containsExactly("edits");
        assertThat(tm.spec().parameters().required()).containsExactly("edits");
    }
    @Disabled("TODO2 A12 open: @Tool without text still produces an empty description. The fix is a start-time validation, not a schema fix.")
    void openQuestion_A12_emptyDescription_whenToolAnnotationCarriesNoText() throws Exception {
        var tm = tool(new NoDescriptionTool(), "bare", String.class);
        assertThat(tm.spec().description())
                .as("TODO2 A12: @Tool ohne Text erzeugt eine namenlose Tool-Spezifikation")
                .isNotBlank();
    }

    // ── A3: Argument-Binding ─────────────────────────────────────────────────

    @Test
    void regression_scalarTypes_bindExactly() throws Exception {
        assertThat(tool(new StringTool(), "echo", String.class).execute("{\"p\":\"x\"}", null).text())
                .isEqualTo("got:[x]");
        assertThat(tool(new PrimitiveTool(), "echo", int.class).execute("{\"p\":42}", null).text())
                .isEqualTo("got:42");
    }

    @Test
    void listOfRecord_isBoundStructurally() throws Exception {
        var tm = tool(new RecordListTool(), "multiEdit", List.class);
        String args = "{\"edits\":[{\"filePath\":\"a.txt\",\"oldString\":\"x\",\"newString\":\"y\"}]}";

        assertThat(tm.execute(args, null).text())
                .as("TODO2 A3 fixed: convertValue yields a real list of records")
                .isEqualTo("applied 1 edits, first=x");
    }

    @Test
    void mapParameter_isBoundStructurally() throws Exception {
        var tm = tool(new MapTool(), "useMap", Map.class);

        assertThat(tm.execute("{\"opts\":{\"a\":1}}", null).text())
                .as("TODO2 A3 fixed: a Map parameter is bound")
                .isEqualTo("keys=[a]");
    }

    @Test
    void enumParameter_isBound() throws Exception {
        var tm = tool(new EnumTool(), "useEnum", EnumTool.Mode.class);

        assertThat(tm.execute("{\"mode\":\"FAST\"}", null).text())
                .as("TODO2 A3 fixed: an enum parameter is bound")
                .isEqualTo("mode=FAST");
    }
    @Disabled("TODO2 A3 partially fixed: convertValue serialises an object for a string parameter into its JSON text. Whether that is the desired contract is open.")
    void defect_A3_stringParameterWithObjectArgument_yieldsEmptyStringSilently() throws Exception {
        var tm = tool(new StringTool(), "echo", String.class);

        // asText() on an ObjectNode yields "" — not an error, a wrong value.
        var result = tm.execute("{\"p\":{\"x\":1}}", null);
        assertThat(result.text())
                .as("TODO2 A3: an object argument for a string parameter must show up as an error")
                .isEqualTo("got:[{\"x\":1}]");
    }

    @Test
    void missingPrimitiveArgument_namesTheArgumentAndTheTool() throws Exception {
        var tm = tool(new PrimitiveTool(), "echo", int.class);

        assertThatThrownBy(() -> tm.execute("{}", null))
                .as("TODO2 A3 fixed: a meaningful message instead of an NPE from ValueConversions")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'p'")
                .hasMessageContaining("echo");
    }

    // ── A7: state aus dem Aufruf statt aus dem ThreadLocal ─────────────────

    @Test
    void regression_agentSessionState_isInjected() throws Exception {
        var tm = tool(new StateParamTool(), "useState", AgentSessionState.class, String.class);
        var state = AgentSessionState.create("sess-42");
        var result = tm.execute("{\"p\":\"v\"}", state);
        assertThat(result.text()).isEqualTo("session=sess-42 p=v");
    }

    @Test
    void stateArgument_winsOverTheThreadLocal() throws Exception {
        var tm = tool(new StateParamTool(), "useState", AgentSessionState.class, String.class);
        var argumentState = AgentSessionState.create("from-argument");
        var threadLocalState = AgentSessionState.create("from-threadlocal");

        de.augmentia.quad.core.session.CurrentSession.setCurrent(threadLocalState);
        try {
            var result = tm.execute("{\"p\":\"v\"}", argumentState);
            assertThat(result.text())
                    .as("TODO2 A7: der state aus execute() muss gewinnen, nicht der ThreadLocal")
                    .isEqualTo("session=from-argument p=v");
        } finally {
            de.augmentia.quad.core.session.CurrentSession.setCurrent(null);
        }
    }
}