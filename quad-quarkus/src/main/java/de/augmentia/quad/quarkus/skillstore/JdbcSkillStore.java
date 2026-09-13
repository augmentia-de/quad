package de.augmentia.quad.quarkus.skillstore;

import de.augmentia.quad.core.capability.skill.SkillEntry;
import de.augmentia.quad.core.capability.skill.SkillStore;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-backed {@link SkillStore} ({@code skills} table, Port 06).
 */
@ApplicationScoped
public class JdbcSkillStore implements SkillStore {

    private static final Logger log = Logger.getLogger(JdbcSkillStore.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final com.fasterxml.jackson.core.type.TypeReference<List<String>> LIST_TYPE = new com.fasterxml.jackson.core.type.TypeReference<>() {};

    @Inject
    DataSource dataSource;

    public JdbcSkillStore() {}

    JdbcSkillStore(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public void upsert(String id, String name, String description, String instructions,
                       List<String> allowedTools, List<String> declaredTools, Map<String, String> meta) {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM skills WHERE name=?")) {
                    del.setString(1, name);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO skills (id, name, description, instructions, allowed_tools, declared_tools, metadata, created_at) VALUES (?,?,?,?,?,?,?,?)")) {
                    ins.setString(1, id);
                    ins.setString(2, name);
                    ins.setString(3, description != null ? description : "");
                    ins.setString(4, instructions);
                    ins.setString(5, toJson(allowedTools));
                    ins.setString(6, toJson(declaredTools));
                    ins.setString(7, toJson(meta));
                    ins.setTimestamp(8, Timestamp.from(Instant.now()));
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
            throw new IllegalStateException("Failed to upsert skill " + name, e);
        }
    }

    @Override
    public Optional<SkillEntry> findByName(String name) {
        String sql = "SELECT id, name, description, instructions, allowed_tools, declared_tools, metadata, created_at FROM skills WHERE name=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(toEntry(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load skill " + name, e);
        }
        return Optional.empty();
    }

    @Override
    public List<SkillEntry> listAll() {
        String sql = "SELECT id, name, description, instructions, allowed_tools, declared_tools, metadata, created_at FROM skills ORDER BY created_at";
        List<SkillEntry> entries = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) entries.add(toEntry(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list skills", e);
        }
        return entries;
    }

    @Override
    public boolean hasSkills() {
        String sql = "SELECT COUNT(*) FROM skills";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count skills", e);
        }
    }

    @Override
    public void deleteByName(String name) {
        String sql = "DELETE FROM skills WHERE name=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete skill " + name, e);
        }
    }

    private SkillEntry toEntry(ResultSet rs) throws SQLException {
        return new SkillEntry(
            rs.getString("id"), rs.getString("name"), rs.getString("description"),
            rs.getString("instructions"), fromJsonList(rs.getString("allowed_tools")),
            fromJsonList(rs.getString("declared_tools")), fromJsonMap(rs.getString("metadata")),
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : Instant.now());
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try { return MAPPER.writeValueAsString(list); } catch (Exception e) {
            log.warn("Failed to serialize skill tools", e);
            return null;
        }
    }

    private String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) return null;
        try { return MAPPER.writeValueAsString(map); } catch (Exception e) {
            log.warn("Failed to serialize skill metadata", e);
            return null;
        }
    }

    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return MAPPER.readValue(json, LIST_TYPE); } catch (Exception e) {
            log.warn("Failed to parse skill tools", e);
            return List.of();
        }
    }

    private Map<String, String> fromJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}); } catch (Exception e) {
            log.warn("Failed to parse skill metadata", e);
            return Map.of();
        }
    }
}
