package de.augmentia.quad.core.connectors;

/** Type of data flow supported by a connector. */
public enum EventType {
    /** Connector receives events from external source (webhooks, polling). */
    INCOMING,
    /** Connector sends actions to external target (messages, updates). */
    OUTGOING,
    /** Both directions. */
    BOTH
}