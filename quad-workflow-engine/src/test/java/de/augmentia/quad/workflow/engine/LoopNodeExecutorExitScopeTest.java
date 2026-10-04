package de.augmentia.quad.workflow.engine;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.workflow.engine.internal.GraphUtils;
import de.augmentia.quad.workflow.engine.internal.RunStateWriter;
import de.augmentia.quad.workflow.engine.internal.WorkflowExecutionContext;
import de.augmentia.quad.workflow.engine.node.NodeExecutor;
import de.augmentia.quad.workflow.engine.node.NodeRunner;
import de.augmentia.quad.workflow.engine.node.executor.LoopNodeExecutor;
import de.augmentia.quad.workflow.engine.port.StepCheckpointPort;
import de.augmentia.quad.workflow.engine.port.WorkflowRunPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exit semantics and cross-iteration data flow of the loop node (TODO2 B2–B5).
 *
 * <p>Needs no container: {@link RunStateWriter} takes its ports in the constructor and
 * {@code LoopNodeExecutor} only knows {@link NodeRunner}.
 */
class LoopNodeExecutorExitScopeTest {

    // ── Stubs ────────────────────────────────────────────────────────────────

    private record NoopRunPort() implements WorkflowRunPort {
        @Override
        public de.augmentia.quad.core.workflow.run.WorkflowRunState create(
                String workflowId, List<String> nodes, String initialData) {
            return de.augmentia.quad.core.workflow.run.WorkflowRunState.start("run-1", workflowId);
        }

        @Override
        public de.augmentia.quad.core.workflow.run.WorkflowRunState load(String runId) {
            return null;
        }

        @Override
        public void save(de.augmentia.quad.core.workflow.run.WorkflowRunState state) {
        }
    }

    private record NoopCheckpoints() implements StepCheckpointPort {
        @Override
        public boolean save(String runId, int stepIndex, String stepStatus,
                            String snapshotJson, String memorySummary) {
            return true;
        }

        @Override
        public Optional<Checkpoint> loadLast(String runId) {
            return Optional.empty();
        }
    }

    private static RunStateWriter writer() {
        return new RunStateWriter(new NoopRunPort(), new NoopCheckpoints());
    }

    private static WorkflowExecutionContext ctx() {
        // LoopNodeExecutor calls saveCheckpoint -> run.runId, so a real run state is required.
        var run = new NoopRunPort().create("wf-test", List.of("L"), "");
        return new WorkflowExecutionContext(run.runId, run, "", "", new AgentSessionState(), false);
    }

    private static NodeRunner fixedOutputs(Map<String, String> outputs, Map<String, Integer> runs) {
        return (node, all, edges, c, skip) -> {
            String id = String.valueOf(node.get("id"));
            runs.merge(id, 1, Integer::sum);
            c.putOutput(id, outputs.getOrDefault(id, ""));
        };
    }

    /** Body that produces a different reviewer verdict per iteration. */
    private static NodeRunner reviewerThatPassesOnSecondRound(Map<String, Integer> runs) {
        return (node, all, edges, c, skip) -> {
            String id = String.valueOf(node.get("id"));
            if ("reviewer".equals(id)) {
                int n = runs.merge("reviewer", 1, Integer::sum);
                c.putOutput("reviewer", n >= 2 ? "OK" : "CRITICAL ERRORS: tool missing");
                return;
            }
            runs.merge(id, 1, Integer::sum);
            c.putOutput("designer", "{\"steps\":[]}");
        };
    }

    private static Map<String, Object> loopNode(String exitCondition, String source) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", "L");
        n.put("type", "loop");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("maxIterations", 3);
        config.put("exitCondition", exitCondition);
        config.put("loopTargetId", "designer");
        if (source != null) config.put("exitConditionSource", source);
        n.put("config", config);
        return n;
    }

    private static List<Map<String, Object>> nodes() {
        return List.of(
                loopNode("contains:OK", null),
                Map.<String, Object>of("id", "designer", "type", "agent"),
                Map.<String, Object>of("id", "reviewer", "type", "agent"));
    }

    private static List<Map<String, Object>> edges() {
        return List.of(
                Map.<String, Object>of("source", "L", "target", "designer"),
                Map.<String, Object>of("source", "designer", "target", "reviewer"),
                Map.<String, Object>of("source", "reviewer", "target", "L"));
    }

    // ── B2: node-related exit predicate ─────────────────────────────────────

    @Test
    void designerOutputContainingOkDoesNotEndTheLoopWhenSourceIsTheReviewer() {
        var ex = new LoopNodeExecutor(writer());
        var outputs = new LinkedHashMap<String, String>();
        outputs.put("designer", "{\"steps\":[{\"note\":\"Looks OK to me\"}]}");
        outputs.put("reviewer", "CRITICAL ERRORS: tool BOOKMARK missing.");
        var runs = new LinkedHashMap<String, Integer>();

        ex.execute(loopNode("equals:OK", "reviewer"), nodes(), edges(), ctx(),
                fixedOutputs(outputs, runs));

        assertThat(runs.getOrDefault("designer", 0))
                .as("TODO2 B2 fixed: with exitConditionSource=reviewer the 'OK' in the "
                        + "Designer-Output den Loop nicht beenden")
                .isEqualTo(3);
    }

    @Test
    void equalReviewerOutputEndsTheLoopAfterOneIteration() {
        var ex = new LoopNodeExecutor(writer());
        var outputs = new LinkedHashMap<String, String>();
        outputs.put("designer", "{\"steps\":[]}");
        outputs.put("reviewer", "OK");
        var runs = new LinkedHashMap<String, Integer>();

        ex.execute(loopNode("equals:OK", "reviewer"), nodes(), edges(), ctx(),
                fixedOutputs(outputs, runs));

        assertThat(runs.getOrDefault("designer", 0))
                .as("TODO2 B2 fixed: equals:OK on the reviewer matches after one round")
                .isEqualTo(1);
    }

    @Test
    void withoutExitConditionSourceTheCombinedOutputIsStillUsed() {
        var ex = new LoopNodeExecutor(writer());
        var outputs = new LinkedHashMap<String, String>();
        outputs.put("designer", "{\"note\":\"Looks OK\"}");
        outputs.put("reviewer", "CRITICAL ERRORS: tool missing.");
        var runs = new LinkedHashMap<String, Integer>();

        // Without the source the old behaviour applies: contains:OK matches the concatenation.
        ex.execute(loopNode("contains:OK", null), nodes(), edges(), ctx(),
                fixedOutputs(outputs, runs));

        assertThat(runs.getOrDefault("designer", 0))
                .as("ohne exitConditionSource gilt weiter der Gesamtoutput")
                .isEqualTo(1);
    }

    @Test
    void iterationBasedExitStillWorks() {
        var ex = new LoopNodeExecutor(writer());
        var outputs = new LinkedHashMap<String, String>();
        outputs.put("designer", "D");
        outputs.put("reviewer", "R");
        var runs = new LinkedHashMap<String, Integer>();

        ex.execute(loopNode("iter>=2", null), nodes(), edges(), ctx(), fixedOutputs(outputs, runs));

        assertThat(runs.getOrDefault("designer", 0)).isEqualTo(2);
    }

    // ── B3/B4: history across iteration boundaries ───────────────────────────

    @Test
    void loopEndsWhenTheReviewerApprovesInARoundTwo() {
        var ex = new LoopNodeExecutor(writer());
        var runs = new LinkedHashMap<String, Integer>();

        ex.execute(loopNode("equals:OK", "reviewer"), nodes(), edges(), ctx(),
                reviewerThatPassesOnSecondRound(runs));

        assertThat(runs.getOrDefault("designer", 0))
                .as("round 1 is rejected, round 2 is released")
                .isEqualTo(2);
    }

    @Test
    void previousRoundOutputSurvivesTheClearBetweenIterations() {
        var ex = new LoopNodeExecutor(writer());
        var seenByDesigner = new ArrayList<String>();
        var runs = new LinkedHashMap<String, Integer>();

        // The designer reads what the reviewer said in the previous round.
        NodeRunner runner = (node, all, edges, c, skip) -> {
            String id = String.valueOf(node.get("id"));
            if ("designer".equals(id)) {
                runs.merge(id, 1, Integer::sum);
                seenByDesigner.add(String.join("|", c.resolveHistory("reviewer", "previousIteration")));
                c.putOutput("designer", "{\"steps\":[]}");
                return;
            }
            int n = runs.merge("reviewer", 1, Integer::sum);
            c.putOutput("reviewer", n >= 2 ? "OK" : "CRITICAL ERRORS: tool missing");
        };

        ex.execute(loopNode("equals:OK", "reviewer"), nodes(), edges(), ctx(), runner);

        assertThat(seenByDesigner)
                .as("TODO2 B3/B4 fixed: round 1 sees nothing yet, round 2 sees the feedback "
                        + "from round 1 — even though the outputs are cleared between rounds")
                .containsExactly("", "CRITICAL ERRORS: tool missing");
    }

    @Test
    void aggregateAllConcatenatesEveryIteration() {
        var ex = new LoopNodeExecutor(writer());
        // "iter>=2" so the loop runs twice — "always" would end it after the first round.
        Map<String, Object> loop = new LinkedHashMap<>(loopNode("iter>=2", null));
        Map<String, Object> config = new LinkedHashMap<>((Map<String, Object>) loop.get("config"));
        config.put("aggregate", "all");
        loop.put("config", config);

        var outputs = new LinkedHashMap<String, String>();
        outputs.put("designer", "D");
        outputs.put("reviewer", "R");
        var runs = new LinkedHashMap<String, Integer>();

        var ctx = ctx();
        ex.execute(loop, nodes(), edges(), ctx, fixedOutputs(outputs, runs));

        assertThat(ctx.output("L"))
                .as("TODO2 B4: aggregate=all lists every round")
                .contains("All iteration outputs")
                .contains("iteration 1")
                .contains("iteration 2");
    }

    @Test
    void aggregateFirstKeepsTheFirstIteration() {
        var ex = new LoopNodeExecutor(writer());
        Map<String, Object> loop = new LinkedHashMap<>(loopNode("iter>=3", null));
        Map<String, Object> config = new LinkedHashMap<>((Map<String, Object>) loop.get("config"));
        config.put("aggregate", "first");
        loop.put("config", config);

        var outputs = new LinkedHashMap<String, String>();
        outputs.put("designer", "ERSTE-RUNDE");
        outputs.put("reviewer", "R");
        var runs = new LinkedHashMap<String, Integer>();

        var ctx = ctx();
        ex.execute(loop, nodes(), edges(), ctx, fixedOutputs(outputs, runs));

        assertThat(ctx.output("L"))
                .as("TODO2 B4: aggregate=first takes the first round")
                .contains("First iteration output")
                .contains("ERSTE-RUNDE");
    }

    // ── Regression ──────────────────────────────────────────────────────────

    @Test
    void loopBodyIsDerivedFromGraphStructure() {
        var ex = new LoopNodeExecutor(writer());
        var outputs = new LinkedHashMap<String, String>();
        outputs.put("designer", "D");
        outputs.put("reviewer", "R");
        var order = new ArrayList<String>();

        NodeRunner ordering = (node, all, edges, c, skip) -> {
            String id = String.valueOf(node.get("id"));
            order.add(id);
            c.putOutput(id, outputs.get(id));
        };

        ex.execute(loopNode("always", null), nodes(), edges(), ctx(), ordering);

        assertThat(order).startsWith("designer", "reviewer");
    }

    @Test
    void combineOutputsPrefixesEveryNode() {
        var outputs = new LinkedHashMap<String, String>();
        outputs.put("designer", "D");
        outputs.put("reviewer", "OK");

        var combined = GraphUtils.combineOutputs(List.of("designer", "reviewer"), outputs);

        assertThat(combined)
                .as("Das Praefix ist der Grund, warum equals:OK auf den Gesamtoutput nie greift")
                .contains("[designer]: D")
                .contains("[reviewer]: OK");
    }
}