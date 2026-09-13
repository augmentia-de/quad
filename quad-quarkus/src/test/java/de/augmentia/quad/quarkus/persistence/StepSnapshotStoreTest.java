package de.augmentia.quad.quarkus.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StepSnapshotStore — save/loadLast/loadAll/deleteRun, 500-kB limit.
 */
class StepSnapshotStoreTest {

    @TempDir
    Path tempDir;
    private StepSnapshotStore store;

    @BeforeEach
    void setUp() {
        store = new StepSnapshotStore();
        SQLiteConfig config = new SQLiteConfig();
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("snapshot-test.db"));
        store.setDataSource(ds);
        SqlDialect dialect = new SqlDialect();
        dialect.setDbKind("sqlite");
        store.setDialect(dialect);
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
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void saveAndLoadLast() {
        assertTrue(store.save("run-1", 0, "running", "{\"outputs\":{}}", "memory-0"));
        assertTrue(store.save("run-1", 1, "completed", "{\"outputs\":{\"n1\":\"ok\"}}", "memory-1"));

        var last = store.loadLast("run-1");
        assertTrue(last.isPresent());
        assertEquals(1, last.get().stepIndex());
        assertEquals("completed", last.get().stepStatus());
        assertTrue(last.get().environmentState().contains("n1"));
        assertEquals("memory-1", last.get().memorySummary());
    }

    @Test
    void loadAllAscending() {
        store.save("run-2", 0, "running", "{}", null);
        store.save("run-2", 1, "completed", "{}", null);
        store.save("run-2", 2, "completed", "{}", null);

        var all = store.loadAll("run-2");
        assertEquals(3, all.size());
        assertEquals(0, all.get(0).stepIndex());
        assertEquals(2, all.get(2).stepIndex());
    }

    @Test
    void overwriteSameStep() {
        store.save("run-3", 0, "running", "{\"v\":1}", null);
        store.save("run-3", 0, "completed", "{\"v\":2}", "updated");

        var last = store.loadLast("run-3");
        assertTrue(last.isPresent());
        assertEquals("completed", last.get().stepStatus());
        assertEquals("updated", last.get().memorySummary());
    }

    @Test
    void deleteRun() {
        store.save("run-4", 0, "running", "{}", null);
        store.save("run-4", 1, "completed", "{}", null);
        store.deleteRun("run-4");
        assertTrue(store.loadLast("run-4").isEmpty());
    }

    @Test
    void emptyRunIdRejected() {
        assertFalse(store.save("", 0, "running", "{}", null));
        assertFalse(store.save(null, 0, "running", "{}", null));
        assertTrue(store.loadLast("").isEmpty());
        assertTrue(store.loadLast(null).isEmpty());
    }

    @Test
    void snapshotTooLargeRejected() {
        String huge = "x".repeat(StepSnapshotStore.MAX_SNAPSHOT_BYTES + 1);
        assertFalse(store.save("run-5", 0, "running", huge, null));
    }

    @Test
    void isolatedRuns() {
        store.save("run-a", 0, "completed", "{}", "a");
        store.save("run-b", 0, "completed", "{}", "b");

        assertEquals("a", store.loadLast("run-a").get().memorySummary());
        assertEquals("b", store.loadLast("run-b").get().memorySummary());
    }

    @Test
    void loadAllEmpty() {
        assertTrue(store.loadAll("nonexistent").isEmpty());
    }
}
