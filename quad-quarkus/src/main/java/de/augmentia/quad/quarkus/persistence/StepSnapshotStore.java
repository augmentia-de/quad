package de.augmentia.quad.quarkus.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistent per-step run snapshots (Resume/Continue basis).
 *
 * <p>Exactly one snapshot is stored per {@code (runId, stepIndex)} in {@code run_checkpoints}
 * (ON CONFLICT upsert). A snapshot consists of the step status, the JSON of the
 * environment (AgentSessionState/Outputs) and an optional memory summary.
 *
 * <p>Size limit: the environment snapshot is capped at {@value #MAX_SNAPSHOT_BYTES} bytes;
 * larger snapshots are rejected (instead of bloating the DB). This keeps the table lean
 * and runs reconstructable from stable, small snapshots.
 *
 * <p>Thread-safe: no shared connection state; each operation opens its own
 * {@link Connection}.
 */
@ApplicationScoped
public class StepSnapshotStore {

    private static final Logger log = Logger.getLogger(StepSnapshotStore.class);

    /** Size limit of an environment snapshot in bytes (500 kB). */
    public static final int MAX_SNAPSHOT_BYTES = 500 * 1024;

    @Inject
    DataSource dataSource;

    @Inject
    SqlDialect dialect;

    // for tests
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void setDialect(SqlDialect dialect) {
        this.dialect = dialect;
    }

    /**
     * Result of a save/load operation on a run snapshot.
     */
    public record Snapshot(String runId, int stepIndex, String stepStatus,
                           String environmentState, String memorySummary) {

        public static Snapshot of(String runId, int stepIndex, String stepStatus,
                                  String environmentState, String memorySummary) {
            return new Snapshot(runId, stepIndex, stepStatus, environmentState, memorySummary);
        }

        public boolean hasEnvironment() {
            return environmentState != null && !environmentState.isBlank();
        }
    }

    /**
     * Saves a step snapshot (replaces an existing one for the same
     * (runId, stepIndex)).
     *
     * @return {@code true} on success, {@code false} when rejected (snapshot too large,
     *         empty runId) or when a DB error occurred.
     */
    public boolean save(String runId, int stepIndex, String stepStatus,
                        String environmentState, String memorySummary) {
        if (runId == null || runId.isBlank()) return false;
        if (environmentState != null && environmentState.length() > MAX_SNAPSHOT_BYTES) {
            log.warnf("StepSnapshotStore.save: Snapshot for run=%s step=%d exceeded the limit of %d bytes - rejected",
                runId, stepIndex, MAX_SNAPSHOT_BYTES);
            return false;
        }
        String sql = dialect.upsert("run_checkpoints",
            "run_id, step_index, step_status, environment_state, memory_summary, updated_at",
            "run_id, step_index",
            "step_status=excluded.step_status, environment_state=excluded.environment_state, " +
            "memory_summary=excluded.memory_summary, updated_at=CURRENT_TIMESTAMP");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.setInt(2, stepIndex);
            ps.setString(3, stepStatus != null ? stepStatus : "running");
            ps.setString(4, environmentState);
            ps.setString(5, memorySummary);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            log.warnf(e, "StepSnapshotStore.save failed: run=%s step=%d", runId, stepIndex);
            return false;
        }
    }

    /**
     * Loads the last (highest) saved step snapshot of a run.
     */
    public Optional<Snapshot> loadLast(String runId) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        String sql = "SELECT run_id, step_index, step_status, environment_state, memory_summary " +
            "FROM run_checkpoints WHERE run_id = ? " +
            "ORDER BY step_index DESC LIMIT 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            log.warnf(e, "StepSnapshotStore.loadLast failed: run=%s", runId);
        }
        return Optional.empty();
    }

    /**
     * Loads all snapshots eines Runs, ascending nach step index.
     */
    public List<Snapshot> loadAll(String runId) {
        var out = new ArrayList<Snapshot>();
        if (runId == null || runId.isBlank()) return out;
        String sql = "SELECT run_id, step_index, step_status, environment_state, memory_summary " +
            "FROM run_checkpoints WHERE run_id = ? ORDER BY step_index ASC";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            log.warnf(e, "StepSnapshotStore.loadAll failed: run=%s", runId);
        }
        return out;
    }

    /** Removes all snapshots of a run (e.g. on workflow deletion/GDPR). */
    public void deleteRun(String runId) {
        if (runId == null || runId.isBlank()) return;
        String sql = "DELETE FROM run_checkpoints WHERE run_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warnf(e, "StepSnapshotStore.deleteRun failed: run=%s", runId);
        }
    }

    private static Snapshot map(ResultSet rs) throws SQLException {
        return Snapshot.of(
            rs.getString("run_id"),
            rs.getInt("step_index"),
            rs.getString("step_status"),
            rs.getString("environment_state"),
            rs.getString("memory_summary"));
    }
}