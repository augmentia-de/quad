package de.augmentia.quad.quarkus.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent TelemetryStore based on the extended {@code session_events} table.
 * Stores run lifecycle, step and span events and provides a filter query.
 * Replaces {@link AuditStore} (in-memory) for run/step/span data.
 */
@ApplicationScoped
public class TelemetryStore {

    private static final Logger log = Logger.getLogger(TelemetryStore.class);

    @Inject
    DataSource dataSource;

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ── Write ─────────────────────────────────────────────────

    public void recordRunLifecycle(String runId, String event, Instant ts) {
        if (runId == null || runId.isBlank()) return;
        String sql = "INSERT INTO session_events (session_id, event_type, payload, ts, run_id) " +
            "VALUES (?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.setString(2, event);
            ps.setString(3, "{\"runId\":\"" + runId + "\"}");
            ps.setTimestamp(4, Timestamp.from(ts));
            ps.setString(5, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warnf(e, "recordRunLifecycle failed: run=%s", runId);
        }
    }

    public void recordStep(String runId, String stepId, String kind, String status, long durMs) {
        if (runId == null || runId.isBlank()) return;
        String payload = "{\"stepId\":\"" + escape(stepId) + "\",\"kind\":\"" + escape(kind) +
            "\",\"status\":\"" + escape(status) + "\",\"durationMs\":" + durMs + "}";
        String sql = "INSERT INTO session_events (session_id, event_type, payload, ts, run_id) " +
            "VALUES (?, 'STEP', ?, CURRENT_TIMESTAMP, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.setString(2, payload);
            ps.setString(3, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warnf(e, "recordStep failed: run=%s step=%s", runId, stepId);
        }
    }

    public void recordSpan(String spanId, String runId, String stepId, String name, String status) {
        if (runId == null || runId.isBlank()) return;
        String payload = "{\"spanId\":\"" + escape(spanId) + "\",\"name\":\"" + escape(name) +
            "\",\"status\":\"" + escape(status) + "\",\"stepId\":\"" + escape(stepId) + "\"}";
        String sql = "INSERT INTO session_events (session_id, event_type, payload, ts, run_id) " +
            "VALUES (?, 'SPAN', ?, CURRENT_TIMESTAMP, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.setString(2, payload);
            ps.setString(3, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warnf(e, "recordSpan failed: run=%s span=%s", runId, spanId);
        }
    }

    // ── Read / Query ─────────────────────────────────────────

    /**
     * Abfrage with optionalen Filtern. Liwith 500, neueste zuerst.
     * kind/status are per Payload-Inhalt gefiltert (SQLite-hinreichend).
     */
    public List<Map<String, Object>> query(String period, String kind, String status,
                                           String runId, String sessionId) {
        StringBuilder sql = new StringBuilder(
            "SELECT id, session_id, event_type, payload, ts, run_id FROM session_events WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (runId != null && !runId.isBlank()) {
            sql.append(" AND run_id = ?");
            params.add(runId);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            sql.append(" AND session_id = ?");
            params.add(sessionId);
        }
        if (period != null && !"all".equals(period)) {
            Instant cutoff = switch (period) {
                case "today" -> Instant.now().minus(Duration.ofDays(1));
                case "week" -> Instant.now().minus(Duration.ofDays(7));
                case "month" -> Instant.now().minus(Duration.ofDays(30));
                default -> Instant.MIN;
            };
            sql.append(" AND ts >= ?");
            params.add(Timestamp.from(cutoff));
        }

        sql.append(" ORDER BY ts DESC LIMIT 500");

        var out = new ArrayList<Map<String, Object>>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String payload = rs.getString("payload");
                    // kind-Filter
                    if (kind != null && !"all".equals(kind) && payload != null
                            && !payload.contains("\"kind\":\"" + kind + "\"")) {
                        continue;
                    }
                    // status-Filter
                    if (status != null && !"all".equals(status) && payload != null
                            && !payload.contains("\"status\":\"" + status + "\"")) {
                        continue;
                    }
                    var m = new LinkedHashMap<String, Object>();
                    m.put("id", rs.getLong("id"));
                    m.put("sessionId", rs.getString("session_id"));
                    m.put("eventType", rs.getString("event_type"));
                    m.put("payload", payload);
                    m.put("runId", rs.getString("run_id"));
                    Timestamp ts = rs.getTimestamp("ts");
                    m.put("timestamp", ts != null ? ts.toInstant().toString() : null);
                    out.add(m);
                }
            }
        } catch (SQLException e) {
            log.warnf(e, "query failed");
        }
        return out;
    }

    // ── Internal ─────────────────────────────────────────────

    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof String s) ps.setString(i + 1, s);
            else if (p instanceof Timestamp t) ps.setTimestamp(i + 1, t);
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
