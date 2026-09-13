package de.augmentia.quad.quarkus.workflow;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.quarkus.persistence.RunStore;
import de.augmentia.quad.quarkus.workflow.internal.WorkflowExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for the snapshot schema (stage 12): {@code snapshotJson()} serializes loop/
 * join/async state; {@code restoreSnapshot()} reconstructs it for resume.
 */
class WorkflowExecutionContextSnapshotTest {

    private WorkflowExecutionContext newCtx() {
        RunStore store = new RunStore();
        RunStore.RunState run = store.create("wf", List.of("n1", "loop", "n2"));
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

        assertTrue(json.contains("\"version\":2"), json);
        assertTrue(json.contains("\"outputs\""));
        assertTrue(json.contains("\"statuses\""));
        assertTrue(json.contains("\"joinDeadline\""));
        assertTrue(json.contains("\"loopIterations\""));
        assertTrue(json.contains("\"loop\":3"));
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
    void legacySnapshotWithoutLoopIterationsIsBackwardCompatible() {
        // Alter Snapshot (Stufe 07, ohne version/loopIterations) darf weiterhin Outputs liefern.
        WorkflowExecutionContext ctx = newCtx();
        ctx.restoreSnapshot("{\"outputs\":{\"n1\":\"old\"},\"statuses\":{\"n1\":\"completed\"}}");
        assertEquals("old", ctx.output("n1"));
        assertEquals("completed", ctx.statuses().get("n1"));
        assertTrue(ctx.loopIterations().isEmpty());
    }
}
