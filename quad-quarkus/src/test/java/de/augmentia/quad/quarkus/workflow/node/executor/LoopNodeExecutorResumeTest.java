package de.augmentia.quad.quarkus.workflow.node.executor;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.quarkus.persistence.RunStore;
import de.augmentia.quad.quarkus.persistence.SqlDialect;
import de.augmentia.quad.quarkus.persistence.StepSnapshotStore;
import de.augmentia.quad.quarkus.ui.PersistenceStore;
import de.augmentia.quad.quarkus.workflow.internal.RunStateWriter;
import de.augmentia.quad.quarkus.workflow.internal.WorkflowExecutionContext;
import de.augmentia.quad.quarkus.workflow.node.NodeRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic test of the loop resume semantics (stage 12): a loop interrupted in the
 * middle of an iteration resumes at the saved iteration on {@code continue}, without
 * re-running already completed body nodes (no double-run).
 *
 * <p>The fake {@link NodeRunner} replicates the fallback semantics of {@code WorkflowEngine.runNode}:
 * a body node that already has output is skipped (no double-run).
 */
class LoopNodeExecutorResumeTest {

    @TempDir
    Path tempDir;

    private static void injectRunWriter(Object target, RunStateWriter writer) throws Exception {
        var field = LoopNodeExecutor.class.getDeclaredField("runWriter");
        field.setAccessible(true);
        field.set(target, writer);
    }

    private static SqlDialect sqliteDialect() {
        SqlDialect d = new SqlDialect();
        d.setDbKind("sqlite");
        return d;
    }

    /** RunStore mit echtem SQLite-PersistenceStore (analog CDI: {@code @Inject PersistenceStore}). */
    private static RunStore newRunStore(SQLiteDataSource ds) throws Exception {
        RunStore store = new RunStore();
        PersistenceStore ps = new PersistenceStore();
        ps.setDataSource(ds);
        ps.setDialect(sqliteDialect());
        var f = RunStore.class.getDeclaredField("db");
        f.setAccessible(true);
        f.set(store, ps);
        return store;
    }

    private SQLiteDataSource newDb() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("loop-resume.db"));
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS run_checkpoints (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "run_id VARCHAR(64) NOT NULL," +
                "step_index INTEGER NOT NULL," +
                "step_status VARCHAR(32)," +
                "environment_state TEXT," +
                "memory_summary TEXT," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "UNIQUE(run_id, step_index))");
            s.execute("CREATE TABLE IF NOT EXISTS workflow_state (" +
                "id varchar(64) primary key," +
                "workflow_id varchar(64) not null," +
                "status varchar(32) not null default 'running'," +
                "initial_data text," +
                "step_results text," +
                "executing_from varchar(64)," +
                "restart_count int not null default 0," +
                "started_at timestamp not null default CURRENT_TIMESTAMP," +
                "finished_at timestamp," +
                "updated_at timestamp not null default CURRENT_TIMESTAMP," +
                "duration_ms bigint not null default 0," +
                "kind varchar(32)," +
                "step_index integer)");
        }
        return ds;
    }

    private RunStateWriter newRunWriter() throws Exception {
        SQLiteDataSource ds = newDb();
        StepSnapshotStore snapshotStore = new StepSnapshotStore();
        snapshotStore.setDataSource(ds);
        snapshotStore.setDialect(sqliteDialect());
        RunStateWriter writer = new RunStateWriter();
        var rs = RunStateWriter.class.getDeclaredField("runStore");
        rs.setAccessible(true);
        rs.set(writer, newRunStore(ds));
        var ss = RunStateWriter.class.getDeclaredField("snapshotStore");
        ss.setAccessible(true);
        ss.set(writer, snapshotStore);
        return writer;
    }

    private WorkflowExecutionContext ctx(boolean resume) throws Exception {
        RunStore store = newRunStore(newDb());
        RunStore.RunState run = store.create("wf", List.of("loop", "body"));
        return new WorkflowExecutionContext(run.runId, run, "", "", new AgentSessionState(), resume);
    }

    private static final List<Map<String, Object>> ALL_NODES = List.of(
        Map.of("id", "loop", "type", "loop", "config", Map.of("maxIterations", 3, "exitCondition", "iter>=4")),
        Map.of("id", "body", "type", "agent"));
    private static final List<Map<String, Object>> EDGES = List.of(
        Map.of("source", "loop", "target", "body"),
        Map.of("source", "body", "target", "loop"));

    /** Fake-Runner wie {@code WorkflowEngine.runNode}: vorhandener Output → skip (kein Doppel-Run). */
    private static NodeRunner countingRunner(AtomicInteger runs, String key) {
        return (node, all, edges, ctx, skip) -> {
            String id = String.valueOf(node.get("id"));
            if (ctx.hasOutput(id)) return;
            runs.incrementAndGet();
            ctx.putOutput(id, key + "-" + runs.get());
        };
    }

    @Test
    void freshRunRunsBodyThrice() throws Exception {
        RunStateWriter writer = newRunWriter();
        LoopNodeExecutor ex = new LoopNodeExecutor();
        injectRunWriter(ex, writer);

        AtomicInteger bodyRuns = new AtomicInteger();
        WorkflowExecutionContext fresh = ctx(false);
        ex.execute((Map<String, Object>) ALL_NODES.get(0), ALL_NODES, EDGES, fresh,
            countingRunner(bodyRuns, "out"));

        assertEquals(3, bodyRuns.get(), "fresh loop runs body in all 3 iterations");
        assertEquals(3, fresh.loopIterations().get("loop").intValue());
    }

    @Test
    void resumeMidLoopDoesNotReRunCompletedIterations() throws Exception {
        RunStateWriter writer = newRunWriter();
        LoopNodeExecutor ex = new LoopNodeExecutor();
        injectRunWriter(ex, writer);

        // Snapshot restore from checkpoint: iteration 2 was in progress (checkpoint carries loopIterations=2).
        WorkflowExecutionContext resume = ctx(true);
        resume.loopIterations().put("loop", 2);

        AtomicInteger bodyRuns = new AtomicInteger();
        ex.execute((Map<String, Object>) ALL_NODES.get(0), ALL_NODES, EDGES, resume,
            countingRunner(bodyRuns, "resumed"));

        // Iteration 1 (abgeschlossen) wird NICHT erneut aufgerufen (startIter=2). Iteration 2+3 → 2 Runs.
        assertEquals(2, bodyRuns.get(),
            "completed iteration 1 is not re-run; loop continues from iteration 2");
        assertTrue(resume.loopIterations().get("loop") >= 3, "loop progressed past restored iteration");
    }

    @Test
    void resumeAtAlmostDoneIterationOnlyReRunsRemaining() throws Exception {
        RunStateWriter writer = newRunWriter();
        LoopNodeExecutor ex = new LoopNodeExecutor();
        injectRunWriter(ex, writer);

        // Iteration 3 (last) was in progress → only that one is re-run.
        WorkflowExecutionContext resume = ctx(true);
        resume.loopIterations().put("loop", 3);

        AtomicInteger bodyRuns = new AtomicInteger();
        ex.execute((Map<String, Object>) ALL_NODES.get(0), ALL_NODES, EDGES, resume,
            countingRunner(bodyRuns, "iter3"));

        assertEquals(1, bodyRuns.get(), "only the in-progress iteration 3 re-runs");
        assertTrue(resume.loopIterations().get("loop") >= 3, "loop progressed past restored iteration");
        assertTrue(resume.output("body").startsWith("iter3"), "in-progress iteration recomputed the body");
    }
}
