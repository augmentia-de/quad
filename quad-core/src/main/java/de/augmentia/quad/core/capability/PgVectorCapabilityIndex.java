package de.augmentia.quad.core.capability;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public class PgVectorCapabilityIndex implements CapabilityIndex {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String tableName;

    public PgVectorCapabilityIndex(DataSource dataSource, ObjectMapper objectMapper, String tableName) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.tableName = tableName;
    }

    @Override
    public void index(Capability capability) {
        String sql = "INSERT INTO " + tableName + " (name, description, method_ref, source, type, allowed_tenants, score) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (name) DO UPDATE SET description = EXCLUDED.description, method_ref = EXCLUDED.method_ref, " +
                     "source = EXCLUDED.source, type = EXCLUDED.type, allowed_tenants = EXCLUDED.allowed_tenants, score = EXCLUDED.score";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, capability.name());
            stmt.setString(2, capability.description());
            stmt.setString(3, capability.methodRef());
            stmt.setString(4, capability.source());
            stmt.setString(5, capability.type());

            if (capability.allowedTenants() != null && !capability.allowedTenants().isEmpty()) {
                stmt.setString(6, objectMapper.writeValueAsString(capability.allowedTenants()));
            } else {
                stmt.setNull(6, Types.VARCHAR);
            }
            stmt.setDouble(7, capability.score());
            stmt.executeUpdate();
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to index capability: " + capability.name(), e);
        }
    }

    @Override
    public void remove(String name) {
        String sql = "DELETE FROM " + tableName + " WHERE name = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove capability: " + name, e);
        }
    }

    @Override
    public List<Capability> search(String query, int topK) {
        return search(query, topK, null);
    }

    @Override
    public List<Capability> search(String query, int topK, String tenantId) {
        String sql = "SELECT name, description, method_ref, source, type, allowed_tenants, score, " +
                     "ts_rank_cd(to_tsvector('english', name || ' ' || description), query) AS rank " +
                     "FROM " + tableName + ", plainto_tsquery('english', ?) query " +
                     "WHERE (name || ' ' || description) @@ query";

        if (tenantId != null && !tenantId.isBlank()) {
            sql += " AND (allowed_tenants IS NULL OR allowed_tenants = ?::text[] OR allowed_tenants::jsonb @> [?])";
        }

        sql += " ORDER BY rank DESC LIMIT ?";

        List<Capability> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, query);
            if (tenantId != null && !tenantId.isBlank()) {
                String tenantsJson = objectMapper.writeValueAsString(List.of(tenantId));
                stmt.setString(2, tenantId);
                stmt.setString(3, tenantsJson);
            }
            stmt.setInt(tenantId != null && !tenantId.isBlank() ? 4 : 3, topK);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Capability cap = new Capability(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("method_ref"),
                        rs.getString("source"),
                        rs.getString("type"),
                        parseTenants(rs.getString("allowed_tenants")),
                        rs.getDouble("score")
                    );
                    results.add(cap);
                }
            }
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to search capabilities", e);
        }

        return results;
    }

    private java.util.Set<String> parseTenants(String json) {
        if (json == null || json.isBlank()) {
            return java.util.Set.of();
        }
        try {
            return new java.util.HashSet<>(objectMapper.readValue(json, List.class));
        } catch (Exception e) {
            return java.util.Set.of();
        }
    }

    @Override
    public void clear() {
        String sql = "TRUNCATE TABLE " + tableName;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear capability index", e);
        }
    }
}