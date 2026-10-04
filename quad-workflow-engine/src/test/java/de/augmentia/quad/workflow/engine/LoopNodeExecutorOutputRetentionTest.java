package de.augmentia.quad.workflow.engine;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.workflow.engine.internal.RunStateWriter;
import de.augmentia.quad.workflow.engine.internal.WorkflowExecutionContext;
import de.augmentia.quad.workflow.engine.node.NodeRunner;
import de.augmentia.quad.workflow.engine.node.executor.LoopNodeExecutor;
import de.augmentia.quad.workflow.engine.port.StepCheckpointPort;
import de.augmentia.quad.workflow.engine.port.WorkflowRunPort;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is still reachable after a loop iteration — and what {@code retainOutput} does.
 *
 * <p>The finding that led to this test: {@code retainOutput} was read and
 * logged but triggered nothing. The log claimed "retains body outputs of iteration
 * N", even though the outputs are preserved <em>independently</em> of the flag —
 * clearing only happens at the <em>start</em> of the next iteration.
 *
 * <p>The test pins the <em>correct</em> behaviour so that it does not silently get
 * lost again: a subsequent node must be able to reach the outputs of the last
 * iteration via the current value and via {@code iteration:N}.
 */
class LoopNodeExecutorOutputRetentionTest {

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

    private static WorkflowExecutionContext ctx() {
        var run = new NoopRunPort().create("wf-test", List.of("L"), "");
        return new WorkflowExecutionContext(run.runId, run, "", "", new AgentSessionState(), false);
    }

    private static RunStateWriter writer() {
        return new RunStateWriter(new NoopRunPort(), new NoopCheckpoints());
    }

    /** Every body node returns its name plus the iteration number. */
    private static NodeRunner countingBody(Map<String, Integer> runs) {
        return (node, all, edges, c, skip) -> {
            String id = String.valueOf(node.get("id"));
            int n = runs.merge(id, 1, Integer::sum);
            c.putOutput(id, id + "-runde-" + n);
        };
    }

    private static Map<String, Object> loopNode(boolean retainOutput) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("maxIterations", 3);
        config.put("exitCondition", "contains:runde-3");
        config.put("loopTargetId", "worker");
        if (retainOutput) config.put("retainOutput", true);

        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", "L");
        n.put("type", "loop");
        n.put("config", config);
        return n;
    }

    private static List<Map<String, Object>> nodes(boolean retainOutput) {
        return List.of(
                loopNode(retainOutput),
                Map.<String, Object>of("id", "worker", "type", "agent"));
    }

    private static List<Map<String, Object>> edges() {
        return List.of(
                Map.<String, Object>of("source", "L", "target", "worker"),
                Map.<String, Object>of("source", "worker", "target", "L"));
    }

    @Test
    void die_letzte_iteration_bleibt_ohne_retainOutput_erreichbar() {
        var c = ctx();
        var runs = new LinkedHashMap<String, Integer>();
        new LoopNodeExecutor(writer()).execute(
                loopNode(false), nodes(false), edges(), c, countingBody(runs));

        assertThat(runs.getOrDefault("worker", 0)).isEqualTo(3);

        assertThat(c.outputs().get("worker"))
                .as("the current value is the one of the LAST iteration — without retainOutput")
                .isEqualTo("worker-runde-3");

        assertThat(c.resolveHistory("worker", "iteration:3"))
                .as("and the history can request them selectively")
                .containsExactly("worker-runde-3");
    }

    @Test
    void retainOutput_aendert_nichts_am_ergebnis() {
        var without = ctx();
        var with = ctx();
        new LoopNodeExecutor(writer()).execute(
                loopNode(false), nodes(false), edges(), without, countingBody(new LinkedHashMap<>()));
        new LoopNodeExecutor(writer()).execute(
                loopNode(true), nodes(true), edges(), with, countingBody(new LinkedHashMap<>()));

        assertThat(with.outputs().get("worker"))
                .as("retainOutput was and is a no-op — it must not add anything")
                .isEqualTo(without.outputs().get("worker"));
    }

    @Test
    void retainOutput_macht_die_frueheren_iterationen_nicht_erreichbar() {
        var c = ctx();
        new LoopNodeExecutor(writer()).execute(
                loopNode(true), nodes(true), edges(), c, countingBody(new LinkedHashMap<>()));

        assertThat(c.resolveHistory("worker", "last"))
                .as("'last' is the current value")
                .containsExactly("worker-runde-3");
        assertThat(c.resolveHistory("worker", "all"))
                .as("the history keeps all three — that is resolveHistory, not retainOutput")
                .containsExactly("worker-runde-1", "worker-runde-2", "worker-runde-3");
    }
}