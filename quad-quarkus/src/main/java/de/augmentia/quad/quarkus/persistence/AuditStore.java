package de.augmentia.quad.quarkus.persistence;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory audit store with real filters (period/agent/eventType).
 * Functionally identical to the quad-dummy backend's AuditStore.
 */
@ApplicationScoped
public class AuditStore {

    public record AuditLogEntry(String id, String timestamp, String agent, String event, String details) {
        public Map<String, Object> toJson() {
            return Map.of("id", id, "timestamp", timestamp, "agent", agent, "event", event, "details", details);
        }
    }

    private final Map<String, AuditLogEntry> entries = new ConcurrentHashMap<>();

    public void add(String id, String timestamp, String agent, String event, String details) {
        entries.put(id, new AuditLogEntry(id, timestamp, agent, event, details));
    }

    public List<AuditLogEntry> getAll() {
        var all = new ArrayList<>(entries.values());
        all.sort(Comparator.comparing(AuditLogEntry::timestamp).reversed());
        return all;
    }

    /** Filters by period (today/week/month), agent, and eventType — identical to the dummy backend */
    public List<AuditLogEntry> getFiltered(String period, String agentFilter, String eventTypeFilter) {
        var logs = getAll();
        if (period != null && !"all".equals(period)) {
            Instant cutoff = switch (period) {
                case "today" -> Instant.now().minus(Duration.ofDays(1));
                case "week" -> Instant.now().minus(Duration.ofDays(7));
                case "month" -> Instant.now().minus(Duration.ofDays(30));
                default -> Instant.MIN;
            };
            logs = logs.stream()
                .filter(l -> {
                    try {
                        return Instant.parse(l.timestamp()).isAfter(cutoff);
                    } catch (Exception e) {
                        return true;
                    }
                })
                .toList();
        }
        if (agentFilter != null && !"all".equals(agentFilter)) {
            logs = logs.stream().filter(l -> agentFilter.equalsIgnoreCase(l.agent())).toList();
        }
        if (eventTypeFilter != null && !"all".equals(eventTypeFilter)) {
            logs = logs.stream().filter(l -> l.event() != null
                && l.event().toLowerCase(java.util.Locale.ROOT)
                    .contains(eventTypeFilter.toLowerCase(java.util.Locale.ROOT))).toList();
        }
        return logs;
    }
}