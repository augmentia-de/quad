package de.augmentia.quad.core.connectors;

/** Status of a connector in its lifecycle. */
public enum ConnectorStatus {
    IDLE,           // Ready, no activity
    CONNECTING,     // Establishing connection (e.g. to API)
    SYNCING,        // Actively syncing data
    ERROR,          // Last sync failed
    DISCONNECTED    // Intentionally disabled/unavailable
}