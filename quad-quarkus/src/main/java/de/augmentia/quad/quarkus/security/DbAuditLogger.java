package de.augmentia.quad.quarkus.security;

import de.augmentia.quad.core.security.AuditEvent;
import de.augmentia.quad.core.security.AuditLogger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * DB-backed {@link AuditLogger} (Port 03). In addition to the structured row, keeps
 * a bounded in-memory tail for fast UI queries.
 */
@ApplicationScoped
public class DbAuditLogger implements AuditLogger {

    private static final Logger log = Logger.getLogger(DbAuditLogger.class);

    private final boolean enabled;
    private final AuditStore store;
    private final List<AuditEvent> recentEvents = new CopyOnWriteArrayList<>();

    @Inject
    public DbAuditLogger(
            @ConfigProperty(name = "quad.security.audit.mode", defaultValue = "file") String mode,
            AuditStore store) {
        this.enabled = "db".equalsIgnoreCase(mode) || "both".equalsIgnoreCase(mode);
        this.store = store;
    }

    @Override
    public void write(AuditEvent event) {
        if (!enabled) return;
        recentEvents.add(event);
        if (recentEvents.size() > 1000) recentEvents.subList(0, recentEvents.size() - 500).clear();
        try { store.insert(event); } catch (Exception e) {
            log.warnf("Audit DB write failed: %s", e.getMessage());
        }
    }

    public boolean isDbEnabled() { return enabled; }
    public List<AuditEvent> recentEvents() { return List.copyOf(recentEvents); }
    public List<AuditEvent> query(int limit, String sessionId, String eventType) {
        return store.query(limit, sessionId, eventType);
    }
}
