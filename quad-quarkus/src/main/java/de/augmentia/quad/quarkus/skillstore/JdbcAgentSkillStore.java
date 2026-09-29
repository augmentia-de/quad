package de.augmentia.quad.quarkus.skillstore;

import de.augmentia.quad.core.capability.skill.SkillEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC-backed agent-skill assignments ({@code agent_skills} table).
 *
 * <p>Skills are always "active" (available). Only skills assigned to an agent
 * via this table are actually used by that agent — analogous to {@code agent_tools}.
 */
@ApplicationScoped
public class JdbcAgentSkillStore {

    @Inject
    DataSource dataSource;

    /** Returns all skill names assigned to an agent. */
    public List<String> listByAgent(String agentId) {
        String sql = "SELECT skill_name FROM agent_skills WHERE agent_id = ? ORDER BY skill_name";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                var result = new ArrayList<String>();
                while (rs.next()) result.add(rs.getString(1));
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load skills for agent " + agentId, e);
        }
    }

    /** Returns all agent_ids that have at least one skill assigned. */
    public List<String> listAgentsWithSkills() {
        String sql = "SELECT DISTINCT agent_id FROM agent_skills ORDER BY agent_id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            var result = new ArrayList<String>();
            while (rs.next()) result.add(rs.getString(1));
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list agents with skills", e);
        }
    }

    /** Assigns a skill to an agent (idempotent upsert). */
    public void assign(String agentId, String skillName) {
        String sql = "INSERT OR REPLACE INTO agent_skills (agent_id, skill_name) VALUES (?, ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setString(2, skillName);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to assign skill " + skillName + " to agent " + agentId, e);
        }
    }

    /** Removes a skill assignment from an agent. */
    public void unassign(String agentId, String skillName) {
        String sql = "DELETE FROM agent_skills WHERE agent_id = ? AND skill_name = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setString(2, skillName);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to unassign skill " + skillName + " from agent " + agentId, e);
        }
    }

    /** Removes all skill assignments for an agent. */
    public void removeAll(String agentId) {
        String sql = "DELETE FROM agent_skills WHERE agent_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to remove skills for agent " + agentId, e);
        }
    }
}