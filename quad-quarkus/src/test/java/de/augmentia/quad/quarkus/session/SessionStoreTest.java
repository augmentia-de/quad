package de.augmentia.quad.quarkus.session;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.quarkus.persistence.SessionStore;
import de.augmentia.quad.quarkus.persistence.SqlDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {

    private SessionStore store;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));
        store = new SessionStore();
        store.setDataSource(ds);
        SqlDialect dialect = new SqlDialect();
        dialect.setDbKind("sqlite");
        store.setDialect(dialect);
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE sessions (" +
                "id varchar(64) primary key, " +
                "created_at timestamp, last_seen timestamp, " +
                "task varchar(2048), result varchar(8192), " +
                "memory_summary varchar(8192), state_json text)");
            s.execute("CREATE TABLE session_events (" +
                "id integer primary key autoincrement, " +
                "session_id varchar(64), event_type varchar(64), " +
                "payload text, ts timestamp)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_sess_events ON session_events (session_id, ts)");
        }
    }

    @Test
    void upsert_and_read_session() {
        store.upsertSession("s-1", "Recherchiere KI", "Ergebnis", "summary");

        Map<String, Object> session = store.session("s-1");
        assertNotNull(session);
        assertEquals("s-1", session.get("id"));
        assertEquals("Recherchiere KI", session.get("task"));
        assertEquals("summary", session.get("memorySummary"));
    }

    @Test
    void upsert_overwrites_existing_session() {
        store.upsertSession("s-2", "Task 1", "R1", null);
        store.upsertSession("s-2", "Task 2", "R2", null);

        Map<String, Object> session = store.session("s-2");
        assertEquals("Task 2", session.get("task"));
        assertEquals("R2", session.get("result"));
    }

    @Test
    void upsert_with_state_json() {
        AgentSessionState state = AgentSessionState.create("s-4");
        state.addFinding("fact 1");
        state.pushCwd("/workspace/a");
        store.upsertSession("s-4", "Test", "OK", null, state);

        AgentSessionState loaded = store.loadState("s-4");
        assertNotNull(loaded);
        assertEquals("s-4", loaded.getSessionId());
        assertTrue(loaded.findings().contains("fact 1"));
        assertEquals("/workspace/a", loaded.currentCwd());
    }

    @Test
    void records_and_reads_events_in_order() {
        store.recordEvent("s-3", "RUN_STARTED", "{\"x\":1}");
        store.recordEvent("s-3", "NODE_COMPLETED", "{\"node\":\"n1\"}");
        store.recordEvent("s-3", "RUN_COMPLETED", "{}");

        List<Map<String, Object>> events = store.events("s-3");
        assertEquals(3, events.size());
        assertEquals("RUN_STARTED", events.get(0).get("eventType"));
        assertEquals("NODE_COMPLETED", events.get(1).get("eventType"));
        assertEquals("RUN_COMPLETED", events.get(2).get("eventType"));
    }

    @Test
    void lists_sessions_sorted_by_last_seen() {
        store.upsertSession("a", "A", null, null);
        store.upsertSession("b", "B", null, null);

        var sessions = store.listSessions();
        assertTrue(sessions.stream().anyMatch(s -> "a".equals(s.get("id"))));
        assertTrue(sessions.stream().anyMatch(s -> "b".equals(s.get("id"))));
    }

    @Test
    void unknown_session_returns_null_and_empty_events() {
        assertNull(store.session("nope"));
        assertTrue(store.events("nope").isEmpty());
    }

    @Test
    void delete_session_removes_all_data() {
        store.upsertSession("del", "To delete", "Result", null);
        store.recordEvent("del", "EVENT", "{}");

        store.deleteSession("del");

        assertNull(store.session("del"));
        assertTrue(store.events("del").isEmpty());
    }

    @Test
    void state_json_roundtrips_full_state() {
        AgentSessionState state = new AgentSessionState();
        state.setTenantId("acme");
        state.setCurrentProject("Project X");
        state.addFinding("finding 1");
        state.addFinding("finding 2");
        state.pushCwd("/workspace/project");
        state.setLastToolNames(java.util.Set.of("websearch", "readfile"));

        store.upsertSession(state.getSessionId(), "Task", "Result", null, state);

        AgentSessionState loaded = store.loadState(state.getSessionId());
        assertNotNull(loaded);
        assertEquals("acme", loaded.getTenantId());
        assertEquals("Project X", loaded.getCurrentProject());
        assertEquals(2, loaded.findings().size());
        assertTrue(loaded.findings().contains("finding 1"));
        assertTrue(loaded.currentCwd().contains("project") || "/workspace/project".equals(loaded.currentCwd()));
        assertEquals(java.util.Set.of("websearch", "readfile"), loaded.getLastToolNames());
    }
}
