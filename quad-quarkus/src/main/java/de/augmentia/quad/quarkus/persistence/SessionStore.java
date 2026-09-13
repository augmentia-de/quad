package de.augmentia.quad.quarkus.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.SessionStateSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL session store (via Flyway migration).
 * Stores session metadata, event history, and the full
 * {@link AgentSessionState} as JSONB (survives restarts).
 */
@ApplicationScoped
public class SessionStore {

    private static final Logger log = Logger.getLogger(SessionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    DataSource dataSource;

    @Inject
    SqlDialect dialect;

    //for test
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void setDialect(SqlDialect dialect) {
        this.dialect = dialect;
    }
    public void upsertSession(String id, String task, String result, String memorySummary) {
        upsertSession(id, task, result, memorySummary, null);
    }

    public void upsertSession(String id, String task, String result, String memorySummary,
                               AgentSessionState state) {
        if (id == null || id.isBlank()) return;
        String sql = dialect.upsert("sessions",
            "id, created_at, last_seen, task, result, memory_summary, state_json",
            "id",
            "last_seen=CURRENT_TIMESTAMP, task=excluded.task, result=excluded.result, " +
            "memory_summary=excluded.memory_summary, state_json=excluded.state_json");
        try (Connection c = dataSource.getConnection()) {
            String json = state != null ? MAPPER.writeValueAsString(SessionStateSnapshot.from(state)) : null;
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, id);
                ps.setTimestamp(2, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                ps.setTimestamp(3, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                ps.setString(4, truncate(task, 2048));
                ps.setString(5, truncate(result, 8192));
                ps.setString(6, truncate(memorySummary, 8192));
                ps.setString(7, json);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            log.warnf(e, "upsertSession failed: %s", id);
        }
    }

    public void recordEvent(String sessionId, String eventType, String payloadJson) {
        if (sessionId == null || sessionId.isBlank()) return;
        String sql = "insert into session_events (session_id, event_type, payload, ts) values (?, ?, ?, CURRENT_TIMESTAMP)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, eventType);
            ps.setString(3, payloadJson != null ? payloadJson : "");
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warnf(e, "recordEvent failed: %s", sessionId);
        }
    }

    public List<Map<String, Object>> listSessions() {
        String sql = "select id, created_at, last_seen, task, result, memory_summary from sessions order by last_seen desc";
        var out = new ArrayList<Map<String, Object>>();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(row(rs));
            }
        } catch (SQLException e) {
            log.warnf(e, "listSessions failed");
        }
        return out;
    }

    public Map<String, Object> session(String id) {
        String sql = "select id, created_at, last_seen, task, result, memory_summary, state_json from sessions where id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    var m = row(rs);
                    String stateJson = rs.getString("state_json");
                    if (stateJson != null && !stateJson.isBlank()) {
                        m.put("stateJson", stateJson);
                    }
                    return m;
                }
            }
        } catch (SQLException e) {
            log.warnf(e, "session(%s) failed", id);
        }
        return null;
    }

    /** Loads the full AgentSessionState from the DB (null if not present) */
    public AgentSessionState loadState(String sessionId) {
        String sql = "select state_json from sessions where id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("state_json");
                    if (json == null || json.isBlank()) return null;
                    var snapshot = MAPPER.readValue(json, SessionStateSnapshot.class);
                    return snapshot.toState();
                }
            }
        } catch (Exception e) {
            log.warnf(e, "loadState(%s) failed", sessionId);
        }
        return null;
    }

    /** Deletes all data for a session (GDPR) */
    public void deleteSession(String sessionId) {
        try (Connection c = dataSource.getConnection()) {
            try (var ps = c.prepareStatement("delete from session_events where session_id = ?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            }
            try (var ps = c.prepareStatement("delete from sessions where id = ?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warnf(e, "deleteSession(%s) failed", sessionId);
        }
    }

    public List<Map<String, Object>> events(String sessionId) {
        String sql = "select id, event_type, payload, ts from session_events where session_id = ? order by ts asc";
        var out = new ArrayList<Map<String, Object>>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("id", rs.getLong("id"));
                    m.put("eventType", rs.getString("event_type"));
                    m.put("payload", rs.getString("payload"));
                    Timestamp ts = rs.getTimestamp("ts");
                    m.put("timestamp", ts != null ? ts.toInstant().toString() : null);
                    out.add(m);
                }
            }
        } catch (SQLException e) {
            log.warnf(e, "events(%s) failed", sessionId);
        }
        return out;
    }

    private static Map<String, Object> row(ResultSet rs) throws SQLException {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", rs.getString("id"));
        m.put("createdAt", ts(rs.getTimestamp("created_at")));
        m.put("lastSeen", ts(rs.getTimestamp("last_seen")));
        m.put("task", rs.getString("task"));
        m.put("result", rs.getString("result"));
        m.put("memorySummary", rs.getString("memory_summary"));
        return m;
    }

    private static String ts(Timestamp t) {
        return t != null ? t.toInstant().toString() : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}