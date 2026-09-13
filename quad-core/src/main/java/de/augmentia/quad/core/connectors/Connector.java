package de.augmentia.quad.core.connectors;

/**
 * SPI for external service connectors. Connectors bridge external platforms
 * (GitHub, Slack, Gmail, ...) with the QUAD agent runtime.
 */
public interface Connector {

    /** Unique identifier for this connector type (e.g. "github", "slack"). */
    String id();

    /** Human-readable name displayed in UI/admin panels. */
    String name();

    /** Description of what this connector does. */
    String description();

    /** Event types supported by this connector. */
    EventType[] supportedEvents();

    /** Whether this connector is currently active/enabled. */
    boolean isActive();

    /** Set whether this connector is enabled. */
    void setActive(boolean active);

    /** Initialize the connector (connection setup, credential loading). */
    void init();

    /** Clean up resources. */
    void destroy();

    /** Trigger a full sync cycle (pull/push data from/to external service). */
    SyncResult syncAll();

    /** Get the current status of this connector. */
    ConnectorStatus getStatus();

    /** Returns the last error message, or empty if successful. */
    String lastError();

    /** Timestamp of the last successful sync, null if never synced. */
    Long lastSyncTimestamp();

    /** Number of consecutive errors. Resets to 0 on successful sync. */
    int consecutiveErrors();

    record SyncResult(
        boolean success,
        int itemsProcessed,
        String summary,
        long durationMs
    ) {
        public static SyncResult success(int items, String summary, long ms) {
            return new SyncResult(true, items, summary, ms);
        }

        public static SyncResult failure(String error, long ms) {
            return new SyncResult(false, 0, error, ms);
        }
    }
}