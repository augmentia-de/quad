package de.augmentia.quad.quarkus.automation;

import de.augmentia.quad.core.workflow.schedule.ScheduledTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One fire of a {@link ScheduledTask},
 * recorded in its own thread + working folder.
 */
public class TaskRun {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_OK = "ok";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_SKIPPED = "skipped";

    public static final String TRIGGER_SCHEDULE = "schedule";
    public static final String TRIGGER_MANUAL = "manual";
    public static final String TRIGGER_CATCHUP = "catchup";

    private String taskId;
    private String runId = "run-" + randomHex();
    private Instant startedAt = Instant.now();
    private Instant finishedAt;
    private String status = STATUS_RUNNING;
    private String resultText;
    private List<String> artifacts = new ArrayList<>();
    private String error;
    private String trigger = TRIGGER_SCHEDULE;
    private String sessionId;

    public TaskRun() {}
    public TaskRun(String taskId) { this.taskId = taskId; }
    public TaskRun(String taskId, String trigger) { this.taskId = taskId; this.trigger = trigger; }

    private static String randomHex() { return UUID.randomUUID().toString().replace("-", "").substring(0, 10); }
    public static String sessionIdForRun(String runId) { return "__run__" + runId; }
    public String effectiveSessionId() { return sessionId != null && !sessionId.isBlank() ? sessionId : sessionIdForRun(runId); }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("task_id", taskId); map.put("run_id", runId);
        map.put("started_at", epochSec(startedAt));
        map.put("finished_at", finishedAt == null ? null : epochSec(finishedAt));
        map.put("status", status); map.put("result_text", resultText);
        map.put("artifacts", artifacts); map.put("error", error);
        map.put("trigger", trigger); map.put("session_id", effectiveSessionId());
        return map;
    }

    private static Double epochSec(Instant i) {
        return i == null ? null : i.getEpochSecond() + i.getNano() / 1_000_000_000.0;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResultText() { return resultText; }
    public void setResultText(String resultText) { this.resultText = resultText; }
    public List<String> getArtifacts() { return artifacts; }
    public void setArtifacts(List<String> artifacts) { this.artifacts = artifacts != null ? artifacts : new ArrayList<>(); }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
}
