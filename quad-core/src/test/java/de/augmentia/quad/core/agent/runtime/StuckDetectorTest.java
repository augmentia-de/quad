package de.augmentia.quad.core.agent.runtime;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The StuckDetector decides when a run ends even though it still had rounds left.
 *
 * <p>The error direction is asymmetrically one-sided, and the tests are distributed
 * accordingly: <b>far more "must stay silent" than "must fire"</b>. A detector that aborts a
 * legitimate task costs results; a detector that lets a loop through costs tokens. Both are
 * expensive, but the second is silent — you only notice it on the invoice.
 */
class StuckDetectorTest {

    private static StuckDetector.Pattern observe(StuckDetector d, String... rounds) {
        StuckDetector.Pattern pattern = null;
        for (String r : rounds) {
            pattern = d.observe(callsFrom(r));
        }
        return pattern;
    }

    /** "a:1|b:2" becomes two calls a(1) and b(2). */
    private static List<ToolExecutionRequest> callsFrom(String notation) {
        if (notation.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(notation.split("\\|")).map(part -> {
            int k = part.indexOf(':');
            String name = k < 0 ? part : part.substring(0, k);
            String args = k < 0 ? "{}" : part.substring(k + 1);
            return ToolExecutionRequest.builder()
                    .id("c-" + name + args)
                    .name(name)
                    .arguments("{\"p\":\"" + args + "\"}")
                    .build();
        }).toList();
    }

    @Nested
    @DisplayName("reports a stall")
    class Reports {

        @Test
        @DisplayName("identical call twice in a row")
        void repetition() {
            var d = new StuckDetector();
            assertThat(observe(d, "a:1", "a:1"))
                    .as("round 2 is identical to round 1")
                    .isNotNull()
                    .extracting(StuckDetector.Pattern::kind)
                    .isEqualTo(StuckDetector.Kind.REPETITION);
        }

        @Test
        @DisplayName("identical call only after three rounds — the warmup stays silent")
        void warmupStaysSilent() {
            var d = new StuckDetector();
            assertThat(observe(d, "a:1"))
                    .as("round 1 is never a pattern")
                    .isNull();
        }

        @Test
        @DisplayName("the same call with different arguments is a different call")
        void differentArgumentsCountAsProgress() {
            var d = new StuckDetector();
            assertThat(observe(d, "a:1", "a:2", "a:3", "a:4", "a:5"))
                    .as("five different arguments are a worked-through list")
                    .isNull();
        }

        @Test
        @DisplayName("A B A B within the window")
        void cycle() {
            var d = new StuckDetector(3, 4);
            var pattern = observe(d, "a:1", "b:1", "a:1", "b:1");
            assertThat(pattern)
                    .as("the sequence repeats")
                    .isNotNull()
                    .extracting(StuckDetector.Pattern::kind)
                    .isEqualTo(StuckDetector.Kind.CYCLE);
        }

        @Test
        @DisplayName("X A B A B — the one-off step neither destroys nor creates the cycle")
        void cycleWithWarmupRuns() {
            var d = new StuckDetector(3, 4);
            assertThat(observe(d, "x:9", "a:1", "b:1", "a:1", "b:1"))
                    .as("after the lead-in A B repeats, that is a cycle")
                    .isNotNull()
                    .extracting(StuckDetector.Pattern::kind)
                    .isEqualTo(StuckDetector.Kind.CYCLE);
        }

        @Test
        @DisplayName("nothing new for several rounds")
        void stallWithoutAnythingNew() {
            // Six different arguments during the ramp-up, then only known ones. That
            // triggers neither repetition nor cycle — the arguments do change — but it is
            // still a stall: the run is not trying anything new any more.
            var d = new StuckDetector(3, 4);
            var pattern = observe(d, "a:1", "a:2", "a:3", "a:4", "a:5", "a:6", "a:1", "a:2", "a:3");
            assertThat(pattern)
                    .as("three rounds without anything new")
                    .isNotNull()
                    .extracting(StuckDetector.Pattern::kind)
                    .isEqualTo(StuckDetector.Kind.STALL);
        }

        @Test
        @DisplayName("the marker is recognisable and names the cause")
        void markerIsHonest() {
            var d = new StuckDetector();
            var pattern = observe(d, "a:1", "a:1");
            assertThat(pattern.marker())
                    .as("same prefix as the round-limit marker, so that a workflow recognises "
                            + "both as incomplete")
                    .startsWith(StuckDetector.Pattern.MARKER)
                    .contains("REPETITION")
                    .contains("Round 2");
        }

        @Test
        @DisplayName("the reason names the concrete call")
        void reasonNamesTheCall() {
            var d = new StuckDetector();
            var pattern = observe(d, "search:q1", "search:q1");
            assertThat(pattern.reason())
                    .contains("search")
                    .contains("q1");
        }
    }

    @Nested
    @DisplayName("stays silent")
    class Silent {

        @Test
        @DisplayName("a long task with changing arguments")
        void longTask() {
            var d = new StuckDetector();
            assertThat(observe(d, "read:a", "read:b", "read:c", "read:d", "read:e", "read:f"))
                    .as("six different files are progress")
                    .isNull();
        }

        @Test
        @DisplayName("the same tool with varying arguments over 30 rounds")
        void longRunIsNotStuck() {
            var d = new StuckDetector();
            StuckDetector.Pattern pattern = null;
            for (int i = 0; i < 30; i++) {
                pattern = d.observe(callsFrom("grep:" + i));
            }
            assertThat(pattern)
                    .as("30 rounds of grep with a narrowing pattern is a search, not a stall")
                    .isNull();
        }

        @Test
        @DisplayName("the tool set changes even though the same one is used")
        void toolSetChanges() {
            var d = new StuckDetector();
            assertThat(observe(d, "a:1", "b:1", "a:2", "c:1", "a:3", "b:2"))
                    .as("mixed but progressing work")
                    .isNull();
        }

        @Test
        @DisplayName("rounds without a tool count as progress")
        void roundWithoutTool() {
            var d = new StuckDetector();
            assertThat(observe(d, "a:1", "", "a:2", "", "a:3"))
                    .as("thinking rounds between calls are not a stall")
                    .isNull();
        }

        @Test
        @DisplayName("after reset() it starts from the beginning")
        void resetStartsFromScratch() {
            var d = new StuckDetector();
            observe(d, "a:1", "a:1");
            d.reset();
            assertThat(d.observe(callsFrom("a:1")))
                    .as("after reset, round 1 is warmup again")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("warmup and cycle window must be sensible")
        void boundariesAreChecked() {
            assertThatThrownBy(() -> new StuckDetector(0, 4))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("minRoundsBeforeSignal");
            assertThatThrownBy(() -> new StuckDetector(3, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cycleWindow");
        }
    }
}