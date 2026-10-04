package de.augmentia.quad.quarkus.workflow;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.workflow.run.WorkflowRunState;
import de.augmentia.quad.quarkus.persistence.RunStore;
import de.augmentia.quad.workflow.engine.internal.WorkflowExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for the snapshot schema (stage 12): {@code snapshotJson()} serializes loop/
 * join/async state; {@code restoreSnapshot()} reconstructs it for resume.
 */
class WorkflowExecutionContextSnapshotTest {

    private WorkflowExecutionContext newCtx() {
        RunStore store = new RunStore();
        WorkflowRunState run = store.create("wf", List.of("n1", "loop", "n2"));
        return new WorkflowExecutionContext(run.runId, run, "", "", new AgentSessionState(), true);
    }

    @Test
    void snapshotJsonCarriesVersionAndLoopIterations() {
        WorkflowExecutionContext ctx = newCtx();
        ctx.putOutput("n1", "out-1");
        ctx.statuses().put("n1", "completed");
        ctx.joinDeadline().put("join1", 123456L);
        ctx.loopIterations().put("loop", 3);

        String json = ctx.snapshotJson();

        assertTrue(json.contains("\"version\":3"), json);
        assertTrue(json.contains("\"outputs\""));
        assertTrue(json.contains("\"statuses\""));
        assertTrue(json.contains("\"joinDeadline\""));
        assertTrue(json.contains("\"loopIterations\""));
        assertTrue(json.contains("\"loop\":3"));
        // Version 3 adds the output history; without it a resumed loop loses the previous round.
        assertTrue(json.contains("\"outputHistory\""), json);
    }

    @Test
    void outputHistorySurvivesASnapshotRoundTrip() {
        WorkflowExecutionContext source = newCtx();
        source.putOutput("reviewer", "iteration-1-feedback");
        source.putOutput("reviewer", "iteration-2-feedback");

        String json = source.snapshotJson();

        WorkflowExecutionContext target = newCtx();
        target.restoreSnapshot(json);

        assertEquals(2, target.outputHistory("reviewer").size());
        assertEquals("iteration-2-feedback", target.outputHistory("reviewer").get(1).value());
    }

    @Test
    void previousIterationResolvesThePriorRoundsValue() {
        WorkflowExecutionContext ctx = newCtx();
        ctx.loopIterations().put("reviewer", 1);
        ctx.putOutput("reviewer", "runde-1");
        ctx.loopIterations().put("reviewer", 2);
        ctx.putOutput("reviewer", "runde-2");

        assertEquals(java.util.List.of("runde-1"),
                ctx.resolveHistory("reviewer", "previousIteration"));
        assertEquals(java.util.List.of("runde-2"),
                ctx.resolveHistory("reviewer", "iteration:2"));
        assertEquals(java.util.List.of("runde-1"),
                ctx.resolveHistory("reviewer", "iteration:1"));
        assertEquals(java.util.List.of("runde-1", "runde-2"),
                ctx.resolveHistory("reviewer", "all"));
        assertEquals(java.util.List.of("runde-2"),
                ctx.resolveHistory("reviewer", "last"));
    }

    @Test
    void clearOutputKeepsTheHistory() {
        WorkflowExecutionContext ctx = newCtx();
        ctx.putOutput("designer", "v1");
        ctx.clearOutput("designer");

        assertTrue(ctx.output("designer") == null);
        assertEquals("v1", ctx.outputHistory("designer").get(0).value());
    }

    @Test
    void legacySnapshotWithoutOutputHistoryRestoresOutputsOnly() {
        WorkflowExecutionContext ctx = newCtx();
        ctx.restoreSnapshot("{\"version\":2,\"outputs\":{\"n1\":\"old\"},\"statuses\":{\"n1\":\"completed\"}}");

        assertEquals("old", ctx.output("n1"));
        assertTrue(ctx.outputHistory("n1").isEmpty(),
                "a v2 snapshot carries no history; the behaviour corresponds to a first run");
    }

    @Test
    void restoreSnapshotReconstructsLoopJoinState() {
        WorkflowExecutionContext source = newCtx();
        source.putOutput("nA", "branch-a");
        source.statuses().put("nA", "completed");
        source.joinDeadline().put("jn", 9876543210L);
        source.loopIterations().put("loop", 2);
        String json = source.snapshotJson();

        WorkflowExecutionContext target = newCtx();
        target.restoreSnapshot(json);

        assertEquals("branch-a", target.output("nA"));
        assertEquals("completed", target.statuses().get("nA"));
        assertEquals(9876543210L, target.joinDeadline().get("jn").longValue());
        assertEquals(2, target.loopIterations().get("loop").intValue());
    }

    @Test
    void restoreSnapshotIgnoresNullAndBlank() {
        WorkflowExecutionContext ctx = newCtx();
        ctx.restoreSnapshot(null);
        ctx.restoreSnapshot("  ");
        assertTrue(ctx.outputs().isEmpty());
        assertTrue(ctx.loopIterations().isEmpty());
    }

    @Test
    void restoreSnapshotUnknownFieldsIsNoop() {
        WorkflowExecutionContext ctx = newCtx();
        ctx.restoreSnapshot("{\"version\":2,\"outputs\":{},\"bogus\":1}");
        assertTrue(ctx.outputs().isEmpty());
        assertTrue(ctx.loopIterations().isEmpty());
    }

    @Test
    void prefixGuardAcceptsIdenticalAndRejectsDivergentFingerprints() {
        WorkflowExecutionContext ctx = new WorkflowExecutionContext("run", null, "", "",
                new AgentSessionState(), false, "block-a", true);

        assertDoesNotThrow(() -> ctx.assertSharedToolFingerprint("block-a", "n1", "fp-a"));
        assertDoesNotThrow(() -> ctx.assertSharedToolFingerprint("block-a", "n3", "fp-a"));
        assertThrows(IllegalStateException.class, () -> ctx.assertSharedToolFingerprint("block-a", "n2", "fp-b"));
    }

    @Test
    void prefixGuardIsGroupedPerPrefixText() {
        WorkflowExecutionContext ctx = new WorkflowExecutionContext("run", null, "", "",
                new AgentSessionState(), false, "prefix-a", true);

        // same group, same fingerprint -> ok
        assertDoesNotThrow(() -> ctx.assertSharedToolFingerprint("prefix-a", "n1", "fp-a"));
        // different group is independent even with a divergent fingerprint
        assertDoesNotThrow(() -> ctx.assertSharedToolFingerprint("prefix-b", "n2", "fp-c"));
        // same group again with same fingerprint still ok
        assertDoesNotThrow(() -> ctx.assertSharedToolFingerprint("prefix-a", "n3", "fp-a"));
        assertThrows(IllegalStateException.class, () -> ctx.assertSharedToolFingerprint("prefix-a", "n4", "fp-b"));
    }

    @Test
    void prefixGuardNoopForNullPrefix() {
        WorkflowExecutionContext ctx = new WorkflowExecutionContext("run", null, "", "",
                new AgentSessionState(), false, null, true);
        assertDoesNotThrow(() -> ctx.assertSharedToolFingerprint(null, "n1", "fp-a"));
        assertDoesNotThrow(() -> ctx.assertSharedToolFingerprint("   ", "n2", "fp-b"));
    }

    @Test
    void agentPrefixedChainAdvancesSkipsAndClears() {
        WorkflowExecutionContext ctx = new WorkflowExecutionContext("run", null, "", "",
                new AgentSessionState(), false, "block-a", true);
        assertEquals("block-a", ctx.currentPrefix());

        ctx.setCurrentPrefix("middle");
        assertEquals("middle", ctx.currentPrefix());

        ctx.clearCurrentPrefix();
        assertEquals(null, ctx.currentPrefix());
    }

    @Test
    void chainSeedsNoPrefixWhenWorkflowFlagOff() {
        WorkflowExecutionContext ctx = new WorkflowExecutionContext("run", null, "", "",
                new AgentSessionState(), false, "block-a", false);
        assertEquals(null, ctx.currentPrefix());
    }

    @Test
    void legacySnapshotWithoutLoopIterationsIsBackwardCompatible() {
        // An old snapshot (stage 07, without version/loopIterations) may still deliver outputs.
        WorkflowExecutionContext ctx = newCtx();
        ctx.restoreSnapshot("{\"outputs\":{\"n1\":\"old\"},\"statuses\":{\"n1\":\"completed\"}}");
        assertEquals("old", ctx.output("n1"));
        assertEquals("completed", ctx.statuses().get("n1"));
        assertTrue(ctx.loopIterations().isEmpty());
    }
}
