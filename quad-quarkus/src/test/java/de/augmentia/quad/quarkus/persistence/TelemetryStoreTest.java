package de.augmentia.quad.quarkus.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TelemetryStore — recordRunLifecycle/recordStep/recordSpan + query with filters.
 */
class TelemetryStoreTest {

    @TempDir
    Path tempDir;
    private TelemetryStore store;

    @BeforeEach
    void setUp() {
        store = new TelemetryStore();
        SQLiteConfig config = new SQLiteConfig();
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("telemetry-test.db"));
        store.setDataSource(ds);
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS session_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "session_id VARCHAR(64) NOT NULL," +
                "event_type VARCHAR(64)," +
                "payload TEXT," +
                "ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "\"index\" INTEGER," +
                "run_id VARCHAR(64))");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void recordRunLifecycleAndQuery() {
        store.recordRunLifecycle("run-1", "RUN_STARTED", Instant.now());
        store.recordRunLifecycle("run-1", "RUN_COMPLETED", Instant.now());

        var results = store.query(null, null, null, "run-1", null);
        assertEquals(2, results.size());
        assertEquals("RUN_COMPLETED", results.get(0).get("eventType")); // newest first
        assertEquals("RUN_STARTED", results.get(1).get("eventType"));
        assertEquals("run-1", results.get(0).get("runId"));
    }

    @Test
    void recordStepAndQuery() {
        store.recordStep("run-2", "n1", "agent", "completed", 150);
        store.recordStep("run-2", "n2", "agent", "failed", 300);

        var results = store.query(null, null, null, "run-2", null);
        assertEquals(2, results.size());
        // kind filter
        var agentSteps = store.query(null, "agent", null, "run-2", null);
        assertEquals(2, agentSteps.size());
        // status filter
        var failedSteps = store.query(null, null, "failed", "run-2", null);
        assertEquals(1, failedSteps.size());
        assertTrue(failedSteps.get(0).get("payload").toString().contains("n2"));
    }

    @Test
    void recordSpanAndQuery() {
        store.recordSpan("span-1", "run-3", "n1", "llm.chat", "OK");

        var results = store.query(null, null, null, "run-3", null);
        assertEquals(1, results.size());
        assertEquals("SPAN", results.get(0).get("eventType"));
        assertTrue(results.get(0).get("payload").toString().contains("llm.chat"));
    }

    @Test
    void sessionIdFilter() {
        store.recordRunLifecycle("run-4", "RUN_STARTED", Instant.now());
        store.recordStep("run-5", "n1", "agent", "completed", 100);

        var r1 = store.query(null, null, null, null, "run-4");
        assertEquals(1, r1.size());
        var r2 = store.query(null, null, null, null, "run-5");
        assertEquals(1, r2.size());
    }

    @Test
    void emptyRunIdRejected() {
        store.recordRunLifecycle("", "RUN_STARTED", Instant.now());
        store.recordStep("", "n1", "agent", "completed", 100);
        store.recordSpan("s", "", "n1", "llm", "OK");

        var results = store.query(null, null, null, "", null);
        assertTrue(results.isEmpty());
    }

    @Test
    void mixedEventsForRun() {
        store.recordRunLifecycle("run-6", "RUN_STARTED", Instant.now());
        store.recordStep("run-6", "n1", "agent", "completed", 200);
        store.recordSpan("span-1", "run-6", "n1", "tool", "OK");
        store.recordRunLifecycle("run-6", "RUN_COMPLETED", Instant.now());

        var all = store.query(null, null, null, "run-6", null);
        assertEquals(4, all.size());
        // lifecycle events
        var lifecycle = store.query(null, null, null, "run-6", null).stream()
            .filter(m -> "RUN_STARTED".equals(m.get("eventType")) || "RUN_COMPLETED".equals(m.get("eventType")))
            .toList();
        assertEquals(2, lifecycle.size());
    }

    @Test
    void payloadContainsEscapedQuotes() {
        store.recordStep("run-7", "n1", "agent", "completed", 50);

        var results = store.query(null, null, null, "run-7", null);
        assertEquals(1, results.size());
        String payload = (String) results.get(0).get("payload");
        assertTrue(payload.contains("\"stepId\":\"n1\""));
        assertTrue(payload.contains("\"kind\":\"agent\""));
    }
}
