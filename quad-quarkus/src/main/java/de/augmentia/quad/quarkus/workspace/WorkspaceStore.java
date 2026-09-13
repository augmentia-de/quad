package de.augmentia.quad.quarkus.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC storage for the workspace registry ({@code workspaces}) and per-session
 * project bindings ({@code project_bindings}). Upserts use delete-then-insert so
 * the SQL stays portable across H2, SQLite and PostgreSQL.
 */
@ApplicationScoped
public class WorkspaceStore {

    private static final Logger log = Logger.getLogger(WorkspaceStore.class);

    @Inject
    DataSource dataSource;

    public WorkspaceStore() {}

    WorkspaceStore(DataSource dataSource) { this.dataSource = dataSource; }

    public void record(String path, String name, String gitBranch) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM workspaces WHERE path=?")) {
                    del.setString(1, path);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO workspaces (path, name, trusted, command_trust, git_branch, last_accessed_at, created_at) "
                        + "VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)")) {
                    ins.setString(1, path);
                    ins.setString(2, name);
                    ins.setInt(3, 0);
                    ins.setString(4, null);
                    ins.setString(5, gitBranch);
                    ins.setTimestamp(6, Timestamp.from(Instant.now()));
                    ins.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record workspace " + path, e);
        }
    }

    public Optional<WorkspaceRecord> byPath(String path) {
        String sql = "SELECT path, name, trusted, command_trust, git_branch, last_accessed_at FROM workspaces WHERE path=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, path);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(toRecord(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load workspace " + path, e);
        }
        return Optional.empty();
    }

    public List<WorkspaceRecord> recent(int limit) {
        String sql = "SELECT path, name, trusted, command_trust, git_branch, last_accessed_at FROM workspaces "
            + "ORDER BY last_accessed_at DESC LIMIT ?";
        List<WorkspaceRecord> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, Math.max(1, limit));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) list.add(toRecord(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list recent workspaces", e);
        }
        return list;
    }

    public List<WorkspaceRecord> trusted() {
        String sql = "SELECT path, name, trusted, command_trust, git_branch, last_accessed_at FROM workspaces "
            + "WHERE trusted = TRUE ORDER BY last_accessed_at DESC";
        List<WorkspaceRecord> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) list.add(toRecord(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list trusted workspaces", e);
        }
        return list;
    }

    public void setTrusted(String path, String commandTrustJson, boolean trusted) {
        byPath(path).ifPresentOrElse(
            w -> setTrustedColumns(path, commandTrustJson, trusted, w.lastAccessedAt()),
            () -> {
                String name = path.substring(Math.max(0, path.lastIndexOf('/') + 1));
                record(path, name, null);
                setTrustedColumns(path, commandTrustJson, trusted, Instant.now());
            });
    }

    private void setTrustedColumns(String path, String commandTrustJson, boolean trusted, Instant lastAccess) {
        String sql = "UPDATE workspaces SET trusted=?, command_trust=?, last_accessed_at=? WHERE path=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, trusted ? 1 : 0);
            stmt.setString(2, commandTrustJson);
            stmt.setTimestamp(3, Timestamp.from(lastAccess != null ? lastAccess : Instant.now()));
            stmt.setString(4, path);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set trust for workspace " + path, e);
        }
    }

    public void setBinding(String sessionId, String kind, String name) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM project_bindings WHERE session_id=? AND kind=?")) {
                    del.setString(1, sessionId);
                    del.setString(2, kind);
                    del.executeUpdate();
                }
                if (name != null && !name.isBlank()) {
                    try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO project_bindings (session_id, kind, name) VALUES (?,?,?)")) {
                        ins.setString(1, sessionId);
                        ins.setString(2, kind);
                        ins.setString(3, name);
                        ins.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store binding for session " + sessionId, e);
        }
    }

    public Optional<String> getBinding(String sessionId, String kind) {
        String sql = "SELECT name FROM project_bindings WHERE session_id=? AND kind=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, kind);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.ofNullable(rs.getString("name"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load binding for session " + sessionId, e);
        }
        return Optional.empty();
    }

    private WorkspaceRecord toRecord(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("last_accessed_at");
        return new WorkspaceRecord(
            rs.getString("path"), rs.getString("name"), rs.getInt("trusted") == 1,
            rs.getString("command_trust"), rs.getString("git_branch"),
            ts != null ? ts.toInstant() : null);
    }
}
