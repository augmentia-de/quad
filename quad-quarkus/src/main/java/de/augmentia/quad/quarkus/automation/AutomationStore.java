package de.augmentia.quad.quarkus.automation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.augmentia.quad.core.workflow.schedule.CronExpression;
import de.augmentia.quad.core.workflow.schedule.Schedule;
import de.augmentia.quad.core.workflow.schedule.ScheduledTask;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC store for scheduled tasks + run history. Tasks/runs are stored as JSON blobs
 * ({@code data}) with indexed columns ({@code next_run}, {@code enabled} bzw.
 * {@code started_at}) — exakt das Schema von V15__automation_normalize.sql
 * ({@code automation_tasks(id, enabled, next_run, data)} und
 * {@code automation_runs(run_id, task_id, started_at, data)}). Der Blob wird in
 * snake_case mit ISO-Instants serialisiert; gemeinsam mit den anderen Backends
 * (Spring) les-/schreibbar.
 */
@ApplicationScoped
public class AutomationStore {

    private static final Logger log = Logger.getLogger(AutomationStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Inject
    DataSource dataSource;

    public AutomationStore() {}

    AutomationStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // -- tasks ------------------------------------------------------------------

    public ScheduledTask save(ScheduledTask task) {
        Instant now = Instant.now();
        task.setUpdatedAt(now);
        task.setNextRun(task.getEnabled() ? computeNextRun(task, now) : null);
        String sql = "INSERT INTO automation_tasks (id, enabled, next_run, data) VALUES (?,?,?,?) "
            + "ON CONFLICT(id) DO UPDATE SET enabled=excluded.enabled, next_run=excluded.next_run, data=excluded.data";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, task.getId());
            stmt.setInt(2, task.getEnabled() ? 1 : 0);
            stmt.setTimestamp(3, toTimestamp(task.getNextRun()));
            stmt.setString(4, toJson(task));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save automation " + task.getId(), e);
        }
        return task;
    }

    public ScheduledTask get(String taskId) {
        List<ScheduledTask> tasks = loadTasks("SELECT data FROM automation_tasks WHERE id=?", taskId, null);
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    public List<ScheduledTask> list() {
        return loadTasks("SELECT data FROM automation_tasks", null, null);
    }

    public List<ScheduledTask> due(Instant now) {
        return loadTasks("SELECT data FROM automation_tasks WHERE enabled=1 AND next_run IS NOT NULL AND next_run<=? ORDER BY next_run",
            null, Timestamp.from(now));
    }

    /** The owning task of a run session ('__run__<run_id>'), or null. */
    public ScheduledTask taskForRunSession(String sessionId) {
        if (sessionId == null || !sessionId.startsWith("__run__")) {
            return null;
        }
        TaskRun run = findRun(sessionId.substring("__run__".length()));
        return run != null ? get(run.getTaskId()) : null;
    }

    private List<ScheduledTask> loadTasks(String sql, String idParam, Timestamp timeParam) {
        List<ScheduledTask> tasks = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (idParam != null) {
                stmt.setString(1, idParam);
            } else if (timeParam != null) {
                stmt.setTimestamp(1, timeParam);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ScheduledTask task = fromJson(rs.getString("data"), ScheduledTask.class);
                    if (task != null) {
                        tasks.add(task);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list automations", e);
        }
        return tasks;
    }

    public boolean delete(String taskId) {
        boolean deleted;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM automation_tasks WHERE id=?")) {
            stmt.setString(1, taskId);
            deleted = stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete automation " + taskId, e);
        }
        deleteRuns(taskId);
        return deleted;
    }

    // -- runs -------------------------------------------------------------------

    public TaskRun addRun(TaskRun run) {
        String sql = "INSERT INTO automation_runs (run_id, task_id, started_at, data) VALUES (?,?,?,?) "
            + "ON CONFLICT(run_id) DO UPDATE SET task_id=excluded.task_id, started_at=excluded.started_at, data=excluded.data";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, run.getRunId());
            stmt.setString(2, run.getTaskId());
            stmt.setTimestamp(3, run.getStartedAt() != null ? Timestamp.from(run.getStartedAt()) : null);
            stmt.setString(4, toJson(run));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save run " + run.getRunId(), e);
        }
        return run;
    }

    public TaskRun findRun(String runId) {
        List<TaskRun> runs = loadRuns("SELECT data FROM automation_runs WHERE run_id=?", runId, 0);
        return runs.isEmpty() ? null : runs.get(0);
    }

    public List<TaskRun> runs(String taskId, int limit) {
        String sql = taskId == null || taskId.isBlank()
            ? "SELECT data FROM automation_runs ORDER BY started_at DESC"
            : "SELECT data FROM automation_runs WHERE task_id=? ORDER BY started_at DESC";
        return loadRuns(sql, taskId == null || taskId.isBlank() ? null : taskId, limit);
    }

    public void deleteRuns(String taskId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM automation_runs WHERE task_id=?")) {
            stmt.setString(1, taskId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete runs of task " + taskId, e);
        }
    }

    private List<TaskRun> loadRuns(String sql, String param, int limit) {
        List<TaskRun> runs = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (param != null) {
                stmt.setString(1, param);
            }
            if (limit > 0) {
                stmt.setMaxRows(limit);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    TaskRun run = fromJson(rs.getString("data"), TaskRun.class);
                    if (run != null) {
                        runs.add(run);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list runs", e);
        }
        return runs;
    }

    // -- next-run computation ---------------------------------------------------

    /**
     * Next fire time (epoch instant), or null if the task is exhausted or a
     * one-shot is past. Honors the task's timezone; "local" means the machine's
     * clock (a local-first tool default).
     */
    public Instant computeNextRun(ScheduledTask task, Instant after) {
        Schedule schedule = task.getSchedule();
        if (schedule == null) {
            return null;
        }
        ZoneId zone = resolveZone(schedule.getTimezone());
        if (Schedule.KIND_ONCE.equals(schedule.getKind())) {
            Instant fire = parseFireAt(schedule.getFireAt(), zone);
            return (task.getRunCount() == 0 && fire != null && fire.isAfter(after)) ? fire : null;
        }
        if (task.getMaxRuns() != null && task.getRunCount() >= task.getMaxRuns()) {
            return null;
        }
        String cron = schedule.getCron();
        if (cron == null || cron.isBlank()) {
            return null;
        }
        return CronExpression.next(cron, after, zone).orElse(null);
    }

    private static Instant parseFireAt(String fireAt, ZoneId zone) {
        if (fireAt == null || fireAt.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(fireAt).toInstant();
        } catch (DateTimeParseException e) {
            // fall through to local parse
        }
        try {
            return LocalDateTime.parse(fireAt).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank() || "local".equalsIgnoreCase(timezone)) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            log.warnf("Unknown timezone '%s' — falling back to local", timezone);
            return ZoneId.systemDefault();
        }
    }

    private static Timestamp toTimestamp(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize automation record", e);
            throw new IllegalStateException("Failed to serialize automation record", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse automation record", e);
            throw new IllegalStateException("Failed to parse automation record", e);
        }
    }
}