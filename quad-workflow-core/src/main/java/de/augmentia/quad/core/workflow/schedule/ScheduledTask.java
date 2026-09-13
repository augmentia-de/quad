package de.augmentia.quad.core.workflow.schedule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A scheduled automation — its own persistent entity; each fire is a fresh run of
 * the task's instructions. Framework-agnostic domain model, shared across backends.
 * Persistence (JDBC) lives in the host module; this class carries the logic-only parts.
 */
public class ScheduledTask {

    private String id = "task-" + randomHex();
    private String title;
    private String instructions;
    private Schedule schedule = new Schedule();
    private String workspace;
    private String originSurface = "cowork";
    private String originSessionId;
    private String agent = "cowork";
    private String taskSessionId;
    private String model;
    private boolean notifyOnCompletion = true;
    private String notifyTarget;
    private List<String> alwaysAllowedTools = new ArrayList<>();
    private List<String> alwaysAllowedCommands = new ArrayList<>();
    private boolean enabled = true;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    private Instant nextRun;
    private Instant lastRun;
    private String lastStatus;
    private int runCount;
    private Integer maxRuns;
    private Instant seenRunsAt = Instant.ofEpochSecond(0);

    public ScheduledTask() {
    }

    public static String newId() {
        return "task-" + randomHex();
    }

    private static String randomHex() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    public static String sessionIdForTask(String taskId) {
        return "__task__" + taskId;
    }

    public String effectiveTaskSessionId() {
        return taskSessionId != null && !taskSessionId.isBlank() ? taskSessionId : sessionIdForTask(id);
    }

    public String ruleEntry(String tool, String target) {
        return target == null || target.isBlank() ? tool : tool + " " + target;
    }

    public boolean addRule(String tool, String target) {
        String entry = ruleEntry(tool, target);
        if (tool == null || tool.isBlank() || target == null || target.isBlank() || alwaysAllowedTools.contains(entry)) {
            return false;
        }
        alwaysAllowedTools.add(entry);
        return true;
    }

    public boolean revokeRule(String entry) {
        return alwaysAllowedTools.remove(entry);
    }

    public static List<String> grantEntries(Object permissions) {
        List<String> entries = new ArrayList<>();
        if (permissions instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof java.util.Map<?, ?> m)) {
                    continue;
                }
                Object access = m.get("access");
                if (!(access instanceof String s) || !"write".equalsIgnoreCase(s)) {
                    continue;
                }
                Object tool = m.get("tool");
                Object target = m.get("target");
                if (!(tool instanceof String t) || !(target instanceof String tg)) {
                    continue;
                }
                if (t.isBlank() || tg.isBlank() || !isGrantableTool(t)) {
                    continue;
                }
                String entry = t + " " + tg;
                if (!entries.contains(entry)) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    private static boolean isGrantableTool(String tool) {
        switch (tool.toLowerCase()) {
            case "run_shell", "execute_bash", "exec", "write_file", "append_file",
                 "multi_edit", "delete_file", "move_file", "copy_file" -> {
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    public java.util.Map<String, Object> publicMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", id);
        map.put("title", title);
        map.put("instructions", instructions);
        map.put("schedule", schedule.human());
        map.put("schedule_raw", schedule.toMap());
        map.put("workspace", workspace);
        map.put("agent", agent);
        map.put("enabled", enabled);
        map.put("next_run", epochSeconds(nextRun));
        map.put("last_run", epochSeconds(lastRun));
        map.put("last_status", lastStatus);
        map.put("run_count", runCount);
        map.put("notify_on_completion", notifyOnCompletion);
        map.put("seen_runs_at", epochSeconds(seenRunsAt) != null ? epochSeconds(seenRunsAt) : 0.0);
        List<Object> allowed = new ArrayList<>();
        for (String entry : alwaysAllowedTools.stream().sorted().toList()) {
            String[] pt = entry.strip().split(" ", 2);
            java.util.Map<String, String> item = new java.util.LinkedHashMap<>();
            item.put("entry", entry);
            item.put("tool", pt[0]);
            if (pt.length > 1) {
                item.put("target", pt[1].strip());
            }
            allowed.add(item);
        }
        map.put("always_allowed", allowed);
        return map;
    }

    private static Double epochSeconds(Instant instant) {
        return instant == null ? null : instant.getEpochSecond() + instant.getNano() / 1_000_000_000.0;
    }

    // getters / setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public Schedule getSchedule() { return schedule; }
    public void setSchedule(Schedule schedule) { this.schedule = schedule != null ? schedule : new Schedule(); }
    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }
    public String getOriginSurface() { return originSurface; }
    public void setOriginSurface(String originSurface) { this.originSurface = originSurface; }
    public String getOriginSessionId() { return originSessionId; }
    public void setOriginSessionId(String originSessionId) { this.originSessionId = originSessionId; }
    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }
    public String getTaskSessionId() { return taskSessionId; }
    public void setTaskSessionId(String taskSessionId) { this.taskSessionId = taskSessionId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public boolean getNotifyOnCompletion() { return notifyOnCompletion; }
    public void setNotifyOnCompletion(boolean notifyOnCompletion) { this.notifyOnCompletion = notifyOnCompletion; }
    public String getNotifyTarget() { return notifyTarget; }
    public void setNotifyTarget(String notifyTarget) { this.notifyTarget = notifyTarget; }
    public List<String> getAlwaysAllowedTools() { return alwaysAllowedTools; }
    public void setAlwaysAllowedTools(List<String> alwaysAllowedTools) { this.alwaysAllowedTools = alwaysAllowedTools != null ? alwaysAllowedTools : new ArrayList<>(); }
    public List<String> getAlwaysAllowedCommands() { return alwaysAllowedCommands; }
    public void setAlwaysAllowedCommands(List<String> alwaysAllowedCommands) { this.alwaysAllowedCommands = alwaysAllowedCommands != null ? alwaysAllowedCommands : new ArrayList<>(); }
    public boolean getEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getNextRun() { return nextRun; }
    public void setNextRun(Instant nextRun) { this.nextRun = nextRun; }
    public Instant getLastRun() { return lastRun; }
    public void setLastRun(Instant lastRun) { this.lastRun = lastRun; }
    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }
    public int getRunCount() { return runCount; }
    public void setRunCount(int runCount) { this.runCount = runCount; }
    public Integer getMaxRuns() { return maxRuns; }
    public void setMaxRuns(Integer maxRuns) { this.maxRuns = maxRuns; }
    public Instant getSeenRunsAt() { return seenRunsAt; }
    public void setSeenRunsAt(Instant seenRunsAt) { this.seenRunsAt = seenRunsAt != null ? seenRunsAt : Instant.ofEpochSecond(0); }
}
