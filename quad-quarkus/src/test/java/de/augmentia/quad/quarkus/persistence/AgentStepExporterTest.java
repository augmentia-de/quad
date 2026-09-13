package de.augmentia.quad.quarkus.persistence;

import de.augmentia.quad.core.events.ModelRequestedEvent;
import de.augmentia.quad.core.events.ToolExecutionFinishedEvent;
import de.augmentia.quad.core.events.ToolExecutionStartedEvent;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentStepExporter} (stage 13): tool/model steps land as STEP records
 * in the telemetry, a single tool call produces exactly one record (no duplicates).
 */
class AgentStepExporterTest {

    @TempDir
    Path tempDir;
    private TelemetryStore telemetryStore;
    private AgentStepExporter exporter;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("agent-steps-test.db"));
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS session_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "session_id VARCHAR(64) NOT NULL," +
                "event_type VARCHAR(64)," +
                "payload TEXT," +
                "ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "\"index\" INTEGER," +
                "run_id VARCHAR(64))");
        }
        telemetryStore = new TelemetryStore();
        telemetryStore.setDataSource(ds);
        exporter = new AgentStepExporter();
        exporter.setTelemetryStore(telemetryStore);
    }

    @Test
    void toolStartFinishProducesSingleStep() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
            .id("r1").name("search").arguments("{\"q\":\"x\"}").build();
        Instant t0 = Instant.now();
        exporter.onEvent(new ToolExecutionStartedEvent("sess-1", t0, req));
        exporter.onEvent(new ToolExecutionFinishedEvent("sess-1", t0.plusMillis(120), "search", false, "ok"));

        var steps = stepsFor("sess-1");
        assertEquals(1, steps.size());
        assertEquals("STEP", steps.get(0).get("eventType"));
        String payload = (String) steps.get(0).get("payload");
        assertTrue(payload.contains("\"kind\":\"tool\""));
        assertTrue(payload.contains("\"status\":\"completed\""));
        int dur = Integer.parseInt(payload.replaceAll(".*\"durationMs\":(\\d+).*", "$1"));
        assertTrue(dur >= 100 && dur <= 120, "durationMs should be within ~120: " + dur);
    }

    @Test
    void failedToolRecordsErrorStatus() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
            .id("r2").name("fs_write").arguments("{}").build();
        Instant t0 = Instant.now();
        exporter.onEvent(new ToolExecutionStartedEvent("sess-2", t0, req));
        exporter.onEvent(new ToolExecutionFinishedEvent("sess-2", t0.plusMillis(50), "fs_write", true, "boom"));

        var steps = stepsFor("sess-2");
        assertEquals(1, steps.size());
        assertTrue(steps.get(0).get("payload").toString().contains("\"status\":\"failed\""));
    }

    @Test
    void repeatedToolCallsAreDistinctNoDuplicate() {
        Instant t0 = Instant.now();
        for (int i = 0; i < 5; i++) {
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("r" + i).name("search").arguments("{}").build();
            exporter.onEvent(new ToolExecutionStartedEvent("sess-3", t0, req));
            exporter.onEvent(new ToolExecutionFinishedEvent("sess-3", t0.plusMillis(10), "search", false, "ok"));
        }
        var steps = stepsFor("sess-3");
        assertEquals(5, steps.size());
    }

    @Test
    void modelRequestedProducesModelStep() {
        exporter.onEvent(new ModelRequestedEvent("sess-4", Instant.now(), List.of()));

        var steps = stepsFor("sess-4");
        assertEquals(1, steps.size());
        assertTrue(steps.get(0).get("payload").toString().contains("\"kind\":\"model\""));
        assertTrue(steps.get(0).get("payload").toString().contains("\"status\":\"started\""));
    }

    @Test
    void interleavedSessionsStayIsolated() {
        Instant t0 = Instant.now();
        exporter.onEvent(new ModelRequestedEvent("sess-a", t0, List.of()));
        ToolExecutionRequest req = ToolExecutionRequest.builder().id("r").name("ls").arguments("{}").build();
        exporter.onEvent(new ToolExecutionStartedEvent("sess-b", t0, req));
        exporter.onEvent(new ToolExecutionFinishedEvent("sess-b", t0.plusMillis(5), "ls", false, "ok"));

        assertEquals(1, stepsFor("sess-a").size());
        assertEquals(1, stepsFor("sess-b").size());
    }

    private List<Map<String, Object>> stepsFor(String sessionId) {
        return telemetryStore.query(null, null, null, sessionId, null);
    }
}
