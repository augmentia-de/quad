package de.augmentia.quad.quarkus.automation;

import de.augmentia.quad.core.workflow.schedule.CronExpression;
import de.augmentia.quad.core.workflow.schedule.Schedule;
import de.augmentia.quad.core.workflow.schedule.ScheduledTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestration layer for automations: CRUD, manual-run lifecycle, and headless
 * execution via {@link AutomationTaskRunner}.
 */
@ApplicationScoped
public class AutomationService {

    private static final Logger log = Logger.getLogger(AutomationService.class);

    @Inject
    AutomationStore store;

    public interface AutomationTaskRunner {
        TaskRun execute(ScheduledTask task, String trigger);
    }

    public void setTaskRunner(AutomationTaskRunner r) { this.taskRunner = r; }
    private volatile AutomationTaskRunner taskRunner;

    // -- REST helpers -----------------------------------------------------------

    public Map<String, Object> list() {
        List<Object> tasks = new ArrayList<>();
        for (ScheduledTask task : store.list()) {
            List<TaskRun> runs = store.runs(task.getId(), 50);
            long unseen = runs.stream().filter(r -> r.getStartedAt().isAfter(task.getSeenRunsAt())).count();
            Map<String, Object> entry = new LinkedHashMap<>(task.publicMap());
            entry.put("unseen_runs", (int) unseen);
            entry.put("unseen_failed", !runs.isEmpty() && "error".equals(runs.get(0).getStatus()));
            tasks.add(entry);
        }
        return Map.of("tasks", tasks);
    }

    public Map<String, Object> get(String taskId) {
        ScheduledTask task = store.get(taskId);
        if (task == null) return Map.of("error", "not found");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", task.publicMap());
        result.put("runs", store.runs(taskId, 50).stream().map(TaskRun::toMap).toList());
        return result;
    }

    public Map<String, Object> create(Map<String, Object> payload) {
        String title = strip(payload.get("title"));
        String instructions = strip(payload.get("instructions"));
        String cron = strip(payload.get("cron"));
        String fireAt = strip(payload.get("fire_at"));
        String timezone = strip(payload.get("timezone")) != null ? strip(payload.get("timezone")) : "local";

        if (title == null) return Map.of("ok", false, "error", "title is required");
        if (instructions == null) return Map.of("ok", false, "error", "instructions are required");
        if ((cron == null || cron.isEmpty()) && (fireAt == null || fireAt.isEmpty())) {
            return Map.of("ok", false, "error", "provide cron or fire_at");
        }
        if (cron != null && !CronExpression.isValid(cron)) {
            return Map.of("ok", false, "error", "invalid cron expression: " + cron);
        }

        Schedule schedule = new Schedule(
            (fireAt != null && cron == null) ? Schedule.KIND_ONCE : Schedule.KIND_CRON,
            cron, fireAt, timezone);

        ScheduledTask task =
            new ScheduledTask();
        task.setTitle(title);
        task.setInstructions(instructions);
        task.setSchedule(schedule);
        task.setWorkspace("");
        task.setOriginSurface("cowork");
        task.setAgent(stripOrDefault(payload.get("agent"), "cowork"));
        task.setModel(strip(payload.get("model")));
        task.setAlwaysAllowedTools(ScheduledTask.grantEntries(payload.get("permissions")));
        store.save(task);
        return Map.of("ok", true, "task", task.publicMap());
    }

    public Map<String, Object> update(String taskId, Map<String, Object> changes) {
        ScheduledTask task = store.get(taskId);
        if (task == null) return Map.of("ok", false, "error", "not found");
        if (changes.containsKey("enabled")) task.setEnabled(Boolean.TRUE.equals(changes.get("enabled")));
        if (changes.get("instructions") != null) task.setInstructions(String.valueOf(changes.get("instructions")));
        if (changes.get("title") != null) task.setTitle(String.valueOf(changes.get("title")));
        if (changes.get("cron") != null) {
            String cron = String.valueOf(changes.get("cron"));
            if (!CronExpression.isValid(cron))
                return Map.of("ok", false, "error", "invalid cron");
            task.getSchedule().setCron(cron);
            task.getSchedule().setKind(Schedule.KIND_CRON);
        }
        if (changes.get("revoke") != null) task.revokeRule(String.valueOf(changes.get("revoke")));
        store.save(task);
        return Map.of("ok", true, "task", task.publicMap());
    }

    public Map<String, Object> delete(String taskId) {
        return Map.of("ok", store.delete(taskId), "id", taskId);
    }

    public Map<String, Object> markSeen(String taskId) {
        ScheduledTask task = store.get(taskId);
        if (task == null) return Map.of("ok", false, "error", "not found");
        task.setSeenRunsAt(Instant.now());
        store.save(task);
        return Map.of("ok", true);
    }

    public Map<String, Object> prepareManualRun(String taskId) {
        ScheduledTask task = store.get(taskId);
        if (task == null) return Map.of("ok", false, "error", "not found");
        TaskRun run = new TaskRun(task.getId(), TaskRun.TRIGGER_MANUAL);
        store.addRun(run);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("run_id", run.getRunId());
        result.put("session_id", run.effectiveSessionId());
        result.put("workspace", task.getWorkspace());
        result.put("agent", task.getAgent());
        result.put("prompt", manualPrompt(task));
        return result;
    }

    public Map<String, Object> finalizeManualRun(String taskId, String runId) {
        ScheduledTask task = store.get(taskId);
        TaskRun run = findRun(taskId, runId);
        if (task == null || run == null) return Map.of("ok", false, "error", "not found");
        if (TaskRun.STATUS_RUNNING.equals(run.getStatus())) {
            run.setResultText(lastAssistantText(run.effectiveSessionId()));
            run.setStatus(TaskRun.STATUS_OK);
            run.setFinishedAt(Instant.now());
            store.addRun(run);
            ScheduledTask fresh = store.get(taskId);
            if (fresh != null) {
                fresh.setLastRun(run.getFinishedAt());
                fresh.setLastStatus(TaskRun.STATUS_OK);
                fresh.setRunCount(fresh.getRunCount() + 1);
                store.save(fresh);
            }
        }
        return Map.of("ok", true, "run", run.toMap());
    }

    public TaskRun runTask(ScheduledTask task, String trigger) {
        TaskRun run = new TaskRun(task.getId(), trigger);
        store.addRun(run);
        if (taskRunner != null) return taskRunner.execute(task, trigger);
        return run;
    }

    // -- internal helpers -------------------------------------------------------

    private TaskRun findRun(String taskId, String runId) {
        for (TaskRun run : store.runs(taskId, 50)) {
            if (run.getRunId().equals(runId)) return run;
        }
        return null;
    }

    private String lastAssistantText(String sessionId) { return "[session-" + sessionId + " transcript]"; }

    private String scheduledPrompt(ScheduledTask task) {
        return "Scheduled run — " + task.getTitle() + "\n\nThis automation is due now: carry out the task below immediately.\n\n"
            + task.getInstructions();
    }

    private String manualPrompt(ScheduledTask task) {
        return "Running automation '" + task.getTitle() + "' now. Carry out these instructions\n\n"
            + task.getInstructions();
    }

    private static String strip(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static String stripOrDefault(Object value, String fallback) {
        String s = strip(value);
        return s != null ? s : fallback;
    }
}
