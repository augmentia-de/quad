package de.augmentia.quad.core.workflow.schedule;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Trigger schedule of a {@link ScheduledTask}. {@code kind} is "cron" (recurring)
 * or "once" (single fire). Framework-agnostic value object.
 */
public class Schedule {

    public static final String KIND_CRON = "cron";
    public static final String KIND_ONCE = "once";

    private String kind = KIND_CRON;
    private String cron;
    private String fireAt; // ISO datetime for one-time fires
    private String timezone = "local";

    public Schedule() {
    }

    public Schedule(String kind, String cron, String fireAt, String timezone) {
        this.kind = kind;
        this.cron = cron;
        this.fireAt = fireAt;
        this.timezone = timezone != null ? timezone : "local";
    }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public String getFireAt() { return fireAt; }
    public void setFireAt(String fireAt) { this.fireAt = fireAt; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone != null ? timezone : "local"; }

    /** Best-effort human label; falls back to the raw cron. */
    public String human() {
        if (KIND_ONCE.equals(kind)) {
            return "Once at " + fireAt;
        }
        String[] parts = cron == null ? new String[0] : cron.trim().split("\\s+");
        if (parts.length != 5) {
            return cron != null ? cron : "?";
        }
        int minute;
        int hour;
        try {
            minute = Integer.parseInt(parts[0]);
            hour = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return cron;
        }
        String time = humanTime(hour, minute);
        String dom = parts[2];
        String dow = parts[4];
        if ("*".equals(dom) && "*".equals(dow)) {
            return "Every day at ~" + time;
        }
        if ("*".equals(dom) && dow.matches("\\d+")) {
            return "Every " + DOW[Integer.parseInt(dow) % 7] + " at ~" + time;
        }
        if (dom.matches("\\d+") && "*".equals(dow)) {
            return "Monthly on day " + dom + " at ~" + time;
        }
        return cron;
    }

    private static String humanTime(int hour, int minute) {
        boolean am = hour < 12;
        int h12 = hour % 12 == 0 ? 12 : hour % 12;
        return h12 + ":" + String.format("%02d", minute) + " " + (am ? "AM" : "PM");
    }

    private static final String[] DOW = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", kind);
        map.put("cron", cron);
        map.put("fire_at", fireAt);
        map.put("timezone", timezone);
        return map;
    }
}
