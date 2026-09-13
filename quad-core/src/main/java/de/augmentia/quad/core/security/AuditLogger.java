package de.augmentia.quad.core.security;

/**
 * Writes audit events to a持久化 backend.
 * Implementations: JsonlAuditLogger (file), InMemoryAuditLogger (testing).
 */
public interface AuditLogger {

    /** Write an audit event. Implementations must be thread-safe. */
    void write(AuditEvent event);

    /** No-op implementation for when auditing is disabled. */
    AuditLogger NOOP = event -> {};
}
