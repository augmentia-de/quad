package de.augmentia.quad.quarkus.security;

import de.augmentia.quad.core.security.AuditEvent;
import de.augmentia.quad.core.security.AuditLogger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Quarkus implementation of AuditLogger.
 * Writes to JSONL file and keeps recent events in memory for REST API.
 */
@ApplicationScoped
public class QuarkusAuditLogger implements AuditLogger {

    private static final Logger log = Logger.getLogger(QuarkusAuditLogger.class);

    private final boolean enabled;
    private final JsonlFileWriter fileWriter;
    private final List<AuditEvent> recentEvents = new CopyOnWriteArrayList<>();

    @Inject
    public QuarkusAuditLogger(
            @ConfigProperty(name = "quad.security.audit.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "quad.security.audit.path", defaultValue = "logs/audit.jsonl") String auditPath) {
        this.enabled = enabled;
        this.fileWriter = enabled ? new JsonlFileWriter(Path.of(auditPath)) : null;
    }

    @Override
    public void write(AuditEvent event) {
        if (!enabled) return;
        recentEvents.add(event);
        // Keep only last 1000 events in memory
        if (recentEvents.size() > 1000) {
            recentEvents.subList(0, recentEvents.size() - 500).clear();
        }
        if (fileWriter != null) {
            try {
                fileWriter.write(event);
            } catch (Exception e) {
                log.warnf("Audit write failed: %s", e.getMessage());
            }
        }
    }

    public List<AuditEvent> recentEvents() {
        return List.copyOf(recentEvents);
    }

    public void clear() {
        recentEvents.clear();
    }
}
