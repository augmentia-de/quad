package de.augmentia.quad.quarkus.memory;

import de.augmentia.quad.core.session.memory.MemoryCategory;
import de.augmentia.quad.core.session.memory.MemoryEntry;
import de.augmentia.quad.core.session.memory.PersistentMemoryStore;
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
import java.util.UUID;

/**
 * JDBC implementation of {@link PersistentMemoryStore} ({@code memories} table).
 */
@ApplicationScoped
public class JdbcMemoryStore implements PersistentMemoryStore {

    private static final Logger log = Logger.getLogger(JdbcMemoryStore.class);

    @Inject
    DataSource dataSource;

    public JdbcMemoryStore() {
    }

    JdbcMemoryStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void remember(MemoryEntry item) {
        if (item.getId() == null) {
            item.setId(UUID.randomUUID().toString());
        }
        if (item.getCreatedAt() == null) {
            item.setCreatedAt(Instant.now());
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM memories WHERE id=?")) {
                    del.setString(1, item.getId());
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO memories (id, scope, content, summary, category, sensitive, created_at) VALUES (?,?,?,?,?,?,?)")) {
                    ins.setString(1, item.getId());
                    ins.setString(2, item.getScope());
                    ins.setString(3, item.getContent());
                    ins.setString(4, item.getSummary());
                    ins.setString(5, item.getCategory() != null ? item.getCategory().name() : null);
                    ins.setBoolean(6, item.isSensitive());
                    ins.setTimestamp(7, Timestamp.from(item.getCreatedAt()));
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
            throw new IllegalStateException("Failed to store memory " + item.getId(), e);
        }
    }

    @Override
    public List<MemoryEntry> findByScope(String scope) {
        String sql = "SELECT id, scope, content, summary, category, sensitive, created_at FROM memories WHERE scope=? ORDER BY created_at DESC";
        List<MemoryEntry> entries = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, scope);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    entries.add(toEntry(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query memories for scope " + scope, e);
        }
        return entries;
    }

    @Override
    public List<MemoryEntry> listAll() {
        String sql = "SELECT id, scope, content, summary, category, sensitive, created_at FROM memories ORDER BY created_at";
        List<MemoryEntry> entries = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                entries.add(toEntry(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list memories", e);
        }
        return entries;
    }

    @Override
    public void updateContent(String id, String content) {
        String sql = "UPDATE memories SET content=?, summary=CASE WHEN summary IS NULL OR summary='' THEN ? ELSE summary END WHERE id=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, content);
            stmt.setString(2, content);
            stmt.setString(3, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update memory " + id, e);
        }
    }

    @Override
    public void deleteAll() {
        String sql = "DELETE FROM memories";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete all memories", e);
        }
    }

    @Override
    public void forget(String id) {
        String sql = "DELETE FROM memories WHERE id=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete memory " + id, e);
        }
    }

    private MemoryEntry toEntry(ResultSet rs) throws SQLException {
        String category = rs.getString("category");
        return new MemoryEntry(
            rs.getString("id"),
            rs.getString("scope"),
            rs.getString("content"),
            rs.getString("summary"),
            category != null ? MemoryCategory.valueOf(category) : null,
            rs.getTimestamp("created_at").toInstant(),
            rs.getBoolean("sensitive")
        );
    }
}
