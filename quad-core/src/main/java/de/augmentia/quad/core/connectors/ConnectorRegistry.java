package de.augmentia.quad.core.connectors;

import de.augmentia.quad.core.connectors.Connector.SyncResult;
import de.augmentia.quad.core.security.SecretsProvider;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry managing all registered {@link Connector} instances.
 * Connectors are discovered automatically or registered programmatically.
 */
public class ConnectorRegistry {

    private final Map<String, Connector> byId = new ConcurrentHashMap<>();
    private SecretsProvider vault;

    public ConnectorRegistry() {
    }

    /** Register a connector instance. Throws if id already exists. */
    public void register(Connector connector) {
        Objects.requireNonNull(connector, "connector must not be null");
        String id = connector.id();
        if (byId.containsKey(id)) {
            throw new IllegalArgumentException("Connector already registered: " + id);
        }
        byId.put(id, connector);
    }

    /** Unregister a connector by its ID. */
    public boolean unregister(String id) {
        Connector c = byId.remove(id);
        if (c != null) {
            try {
                c.destroy();
            } catch (Exception e) {
                // Log but don't fail
            }
        }
        return c != null;
    }

    /** Get a connector by ID. */
    public Optional<Connector> getById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Get an active connector by ID. */
    public Optional<Connector> getActiveById(String id) {
        Connector c = byId.get(id);
        return c != null && c.isActive() ? Optional.of(c) : Optional.empty();
    }

    /** List all registered connectors. */
    public List<Connector> listAll() {
        return List.copyOf(byId.values());
    }

    /** List only active connectors. */
    public List<Connector> listActive() {
        return byId.values().stream().filter(Connector::isActive).collect(Collectors.toList());
    }

    /** Trigger sync on all active connectors. Returns map of results. */
    public Map<String, SyncResult> syncAllConnectors() {
        var results = new LinkedHashMap<String, SyncResult>();
        for (var conn : listActive()) {
            results.put(conn.id(), conn.syncAll());
        }
        return results;
    }

    /** Trigger sync on a specific connector. */
    public Optional<SyncResult> syncConnector(String id) {
        Connector c = byId.get(id);
        if (c == null || !c.isActive()) {
            return Optional.empty();
        }
        return Optional.of(c.syncAll());
    }

    /** Get status snapshot for all connectors. */
    public Map<String, ConnectorStatus> getStatuses() {
        Map<String, ConnectorStatus> result = new LinkedHashMap<>();
        for (var entry : byId.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getStatus());
        }
        return result;
    }

    /** Initialize all registered connectors. Must be called after injection. */
    public void initAll() {
        for (Connector c : byId.values()) {
            c.init();
        }
    }

    /** Set the vault provider for secret resolution during sync. */
    public void setVault(SecretsProvider vault) {
        this.vault = vault;
    }

    /** Discover and register all beans of type Connector from CDI. */
    @SuppressWarnings("unchecked")
    public void discoverAndRegisterAll(jakarta.enterprise.inject.Instance<Connector> connectorBeans) {
        try {
            for (Connector c : connectorBeans) {
                if (c != null) {
                    register(c);
                }
            }
        } catch (IllegalStateException e) {
            // Not running in CDI container — skip auto-discovery
        }
    }

    /** Count registered connectors. */
    public int size() {
        return byId.size();
    }
}