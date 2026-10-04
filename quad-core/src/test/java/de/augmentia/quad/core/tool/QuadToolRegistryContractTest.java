package de.augmentia.quad.core.tool;

import de.augmentia.quad.core.session.AgentSessionState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registry-Vertrag von {@link QuadToolRegistry} (TODO2 A10, A11).
 *
 * <p>Dokumentiert den Vertrag zwischen Registry und Aufrufern: unbekannte Namen, Kollisionen und
 * the question of whether the state is thread-safe. {@link ToolActivatorTool} is tested separately because
 * it packages errors as success.
 */
class QuadToolRegistryContractTest {

    private static ToolArgsMapper mapper() {
        return new ToolArgsMapper(new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static ToolMethod stub(String name, String result) {
        return new ToolMethod() {
            @Override
            public dev.langchain4j.agent.tool.ToolSpecification spec() {
                return dev.langchain4j.agent.tool.ToolSpecification.builder()
                        .name(name).description("stub " + name).build();
            }

            @Override
            public ToolResult execute(String jsonArguments, AgentSessionState state) {
                return ToolResult.success(result);
            }
        };
    }

    // ── A10: get(name) bei unbekanntem Namen ────────────────────────────────

    @Test
    void regression_registerAndGet_roundTrip() {
        var reg = new QuadToolRegistry(mapper());
        reg.register("alpha", stub("alpha", "A"));
        assertThat(reg.get("alpha")).isNotNull();
        assertThat(reg.get("alpha").spec().name()).isEqualTo("alpha");
    }

    @Test
    void getReturnsNullForUnknownName_butFindReturnsEmpty() {
        var reg = new QuadToolRegistry(mapper());
        assertThat(reg.get("gibt-es-nicht"))
                .as("get() bleibt fuer Bestandscode null-liefernd")
                .isNull();
        assertThat(reg.find("gibt-es-nicht"))
                .as("TODO2 A10 fixed: find(String) explicitly separates 'missing' from 'null'")
                .isEmpty();
        assertThat(reg.contains("gibt-es-nicht")).isFalse();
    }

    @Test
    void findReturnsTheToolForAKnownName() {
        var reg = new QuadToolRegistry(mapper());
        reg.register("alpha", stub("alpha", "A"));

        assertThat(reg.find("alpha"))
                .as("TODO2 A10 fixed: Optional<Tool> find(String)")
                .isPresent()
                .get()
                .satisfies(t -> assertThat(t.spec().name()).isEqualTo("alpha"));
    }

    @Test
    void defect_A10_getSpecificationsReturnsRawWildcard() throws Exception {
        var reg = new QuadToolRegistry(mapper());
        reg.register("alpha", stub("alpha", "A"));

        // List<?> forces every consumer to cast. quad has four of them:
        // AgentRuntime:215, Agent:387, PromptAssembler:68, DynamicToolProvider:25.
        var specs = reg.getSpecifications();
        assertThat(specs).hasSize(1);
        @SuppressWarnings("unchecked")
        var first = (dev.langchain4j.agent.tool.ToolSpecification) specs.get(0);
        assertThat(first.name()).isEqualTo("alpha");
    }

    @Test
    void sizeAndToolNamesAreAvailable() {
        var reg = new QuadToolRegistry(mapper());
        reg.register("alpha", stub("alpha", "A"));
        reg.register("beta", stub("beta", "B"));

        assertThat(reg.size())
                .as("TODO2 A10 fixed: size() and getToolNames() added")
                .isEqualTo(2);
        assertThat(reg.getToolNames()).containsExactlyInAnyOrder("alpha", "beta");
    }

    // ── A10: Kollisions-Verhalten ────────────────────────────────────────────

    @Test
    void registerThrowsOnNameCollision() throws Exception {
        var reg = new QuadToolRegistry(mapper());
        reg.register("dup", stub("dup", "first"));

        assertThatThrownBy(() -> reg.register("dup", stub("dup", "second")))
                .as("TODO2 A10 fixed: last-wins was silent, now DuplicateToolNameException")
                .isInstanceOf(QuadToolRegistry.DuplicateToolNameException.class)
                .hasMessageContaining("dup");
        assertThat(reg.get("dup").execute("{}", null).text())
                .as("das erste registrierte Tool bleibt gewinnen")
                .isEqualTo("first");
    }

    // ── A10: withOnly ────────────────────────────────────────────────────────

    @Test
    void regression_withOnly_returnsAFilteredCopy() {
        var reg = new QuadToolRegistry(mapper());
        reg.register("alpha", stub("alpha", "A"));
        reg.register("beta", stub("beta", "B"));

        var filtered = reg.withOnly(Set.of("alpha"));
        assertThat(filtered.get("alpha")).isNotNull();
        assertThat(filtered.get("beta")).isNull();
        // Original bleibt unberuehrt
        assertThat(reg.get("beta")).isNotNull();
    }

    @Test
    void regression_removeDropsTheTool() {
        var reg = new QuadToolRegistry(mapper());
        reg.register("alpha", stub("alpha", "A"));
        reg.remove("alpha");
        assertThat(reg.get("alpha")).isNull();
    }

    @Test
    void regression_getAllAndGetByNamesWork() {
        var reg = new QuadToolRegistry(mapper());
        reg.register("alpha", stub("alpha", "A"));
        reg.register("beta", stub("beta", "B"));

        assertThat(reg.getAll()).hasSize(2);
        assertThat(reg.getByNames(List.of("alpha", "unknown"))).hasSize(1);
    }

    // ── A11: notifyToolSetChanged ist ein No-op ──────────────────────────────

    @Test
    void defect_A11_notifyToolSetChangedDoesNothing() {
        var reg = new QuadToolRegistry(mapper());
        var added = new LinkedHashSet<>(Set.of("alpha"));
        var removed = new LinkedHashSet<String>();

        reg.notifyToolSetChanged(added, removed);

        // TODO2 A11: die Methode muesste ein ToolSetChangedEvent feuern. Sie tut nichts.
        // After the fix an observation should be possible here — today there is none.
        assertThat(reg.get("alpha"))
                .as("notifyToolSetChanged feuert kein Event; der Aufrufer kann nicht reagieren")
                .isNull();
    }

    // ── A10: threadsafety ────────────────────────────────────────────────────

    @Test
    void defect_A10_linkedHashMapWithoutSynchronization() {
        // TODO2 A10: beide nutzen LinkedHashMap ohne Synchronisation und erlauben
        // register/remove waehrend eines laufenden Agenten. Der Test dokumentiert, dass
        // ein ganzzahlig benannter Bestand konsistent bleibt — die Race-Condition
        // selbst ist nicht deterministisch testbar.
        var reg = new QuadToolRegistry(mapper());
        for (int i = 0; i < 50; i++) {
            reg.register("t" + i, stub("t" + i, "v" + i));
        }
        assertThat(reg.getAll()).hasSize(50);
        assertThat(reg.getAll().stream().map(t -> t.spec().name())).doesNotHaveDuplicates();
    }
}