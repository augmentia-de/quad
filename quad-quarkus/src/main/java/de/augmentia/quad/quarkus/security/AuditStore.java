package de.augmentia.quad.quarkus.security;

import de.augmentia.quad.core.security.AuditEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC reader/writer over the {@code audit_events} table (Port 03). Structured rows
 * mirror the core {@link AuditEvent} record.
 */
@ApplicationScoped
public class AuditStore {

    private static final Logger log = Logger.getLogger(AuditStore.class);

    @Inject
    DataSource dataSource;

    public AuditStore() {}

    AuditStore(DataSource dataSource) { this.dataSource = dataSource; }

    /** Inserts an audit event as a structured row. */
    public void insert(AuditEvent event) {
        String sql = "INSERT INTO audit_events (event_type, user_id, roles, session_id, tool_name, tool_args, "
            + "result, is_error, duration_ms, correlation_id, token_id, ts) VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, event.eventType());
            stmt.setString(2, event.userId());
            stmt.setString(3, event.roles() != null ? String.join(",", event.roles()) : null);
            stmt.setString(4, event.sessionId());
            stmt.setString(5, event.toolName());
            stmt.setString(6, event.toolArgs());
            stmt.setString(7, event.result());
            stmt.setBoolean(8, event.isError());
            stmt.setLong(9, event.durationMs());
            stmt.setString(10, event.correlationId());
            stmt.setString(11, event.tokenId());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist audit event", e);
        }
    }

    /** Queries events, newest first, with optional filters. */
    public List<AuditEvent> query(int limit, String sessionId, String eventType) {
        StringBuilder sql = new StringBuilder(
            "SELECT event_type, user_id, roles, session_id, tool_name, tool_args, result, is_error, "
            + "duration_ms, correlation_id, token_id, ts FROM audit_events WHERE 1=1");
        List<String> params = new ArrayList<>();
        if (sessionId != null && !sessionId.isBlank()) {
            sql.append(" AND session_id=?");
            params.add(sessionId);
        }
        if (eventType != null && !eventType.isBlank()) {
            sql.append(" AND event_type=?");
            params.add(eventType);
        }
        sql.append(" ORDER BY ts DESC LIMIT ?");
        List<AuditEvent> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int i = 1;
            for (String p : params) stmt.setString(i++, p);
            stmt.setInt(i, Math.max(1, Math.min(limit <= 0 ? 200 : limit, 1000)));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) out.add(toEvent(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query audit events", e);
        }
        return out;
    }

    private AuditEvent toEvent(ResultSet rs) throws SQLException {
        String rolesStr = rs.getString("roles");
        java.util.Set<String> roles = rolesStr == null || rolesStr.isBlank()
            ? java.util.Set.of() : java.util.Set.of(rolesStr.split(","));
        Instant ts = rs.getTimestamp("ts") != null ? rs.getTimestamp("ts").toInstant() : Instant.now();
        return new AuditEvent(
            ts, rs.getString("user_id"), roles, rs.getString("session_id"),
            rs.getString("tool_name"), rs.getString("tool_args"), rs.getString("result"),
            rs.getBoolean("is_error"), rs.getLong("duration_ms"),
            rs.getString("correlation_id"), rs.getString("token_id"), rs.getString("event_type"));
    }
}
