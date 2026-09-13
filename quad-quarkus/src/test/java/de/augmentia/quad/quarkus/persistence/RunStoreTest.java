package de.augmentia.quad.quarkus.persistence;

import de.augmentia.quad.quarkus.ui.PersistenceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** DB roundtrip test for stage 03 (Obs 1): RunState extended with kind/stepIndex. */
class RunStoreTest {

    private PersistenceStore db;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource ds = new SQLiteDataSource(new SQLiteConfig());
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));
        db = new PersistenceStore();
        db.setDataSource(ds);
        SqlDialect dialect = new SqlDialect();
        dialect.setDbKind("sqlite");
        db.setDialect(dialect);
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            // Schema entspricht V14 (workflow_state) + V17 (kind/step_index).
            s.execute("CREATE TABLE workflow_state (" +
                "id varchar(64) primary key, workflow_id varchar(64) not null, " +
                "status varchar(32) not null default 'running', initial_data text, " +
                "step_results text, executing_from varchar(64), " +
                "restart_count int not null default 0, " +
                "started_at timestamp not null default CURRENT_TIMESTAMP, finished_at timestamp, " +
                "updated_at timestamp not null default CURRENT_TIMESTAMP, " +
                "duration_ms bigint not null default 0, kind varchar(32), step_index integer)");
        }
    }

    @Test
    void kind_and_stepIndex_survive_db_roundtrip() {
        RunStore.RunState state = new RunStore.RunState("run-test-1", "wf-1");
        state.status = "running";
        state.kind = "AGENT";
        state.stepIndex = 5;

        db.upsertRun(state);

        RunStore.PersistedRun loaded = db.loadRunFromDb("run-test-1");
        assertNotNull(loaded);
        assertEquals("AGENT", loaded.kind());
        assertEquals(5, loaded.stepIndex());
    }

    @Test
    void defaults_apply_when_kind_or_stepIndex_null() {
        // Simuliert Bestands-Run ohne V17-Spaltenwerte (Rollback-Absicherung MAO2 §3.1).
        db.upsertRun(new RunStore.RunState("run-test-2", "wf-2"));

        RunStore.PersistedRun loaded = db.loadRunFromDb("run-test-2");
        assertNotNull(loaded);

        RunStore.RunState restored = RunStore.fromPersisted(loaded);
        assertEquals("WORKFLOW", restored.kind);
        assertEquals(0, restored.stepIndex);
    }

    @Test
    void snapshot_and_toJson_carry_kind_and_stepIndex() {
        RunStore.RunState state = new RunStore.RunState("run-test-3", "wf-3");
        state.kind = "WORKFLOW";
        state.stepIndex = 2;

        RunStore.WorkflowRun snapshot = state.snapshot();
        assertEquals("WORKFLOW", snapshot.kind());
        assertEquals(2, snapshot.stepIndex());
        assertEquals("WORKFLOW", snapshot.toJson().get("kind"));
        assertEquals(2, snapshot.toJson().get("stepIndex"));
    }
}