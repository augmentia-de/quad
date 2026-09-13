package de.augmentia.quad.core.connectors;

import de.augmentia.quad.core.security.SecretsProvider;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * Abstract base implementation for {@link Connector} providing common patterns:
 * <ul>
 *   <li>Credential resolution via {@link SecretsProvider}</li>
 *   <li>Status tracking (lastSync, consecutiveErrors)</li>
 *   <li>Error logging with masked secrets</li>
 * </ul>
 * Concrete connectors extend this and implement {@code syncAll()} plus any platform-specific methods.
 */
public abstract class BaseConnector implements Connector {

    private static final Logger log = Logger.getLogger(BaseConnector.class);

    protected boolean active = false;
    protected ConnectorStatus status = ConnectorStatus.IDLE;
    protected String lastError;
    protected long lastSyncTimestamp;
    protected int consecutiveErrors = 0;

    // Set by init() from CDI config or injected
    protected transient SecretsProvider vault;
    protected java.util.Map<String, Object> config;

    /** Default constructor for CDI instantiation. */
    protected BaseConnector() {
        this.config = new java.util.LinkedHashMap<>();
    }

    /** Constructor for tests — skip Vault dependency. */
    protected BaseConnector(SecretsProvider vault) {
        this.vault = vault;
        this.config = new java.util.LinkedHashMap<>();
    }

    @Override
    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public ConnectorStatus getStatus() {
        return status;
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public Long lastSyncTimestamp() {
        return lastSyncTimestamp > 0 ? lastSyncTimestamp : null;
    }

    @Override
    public int consecutiveErrors() {
        return consecutiveErrors;
    }

    /** Initialize the connector using the configured SecretsProvider. */
    @Override
    public abstract void init();

    /** Perform a full sync cycle. Override in subclass. */
    @Override
    public SyncResult syncAll() {
        if (!active || vault == null || !vault.isEnabled()) {
            return SyncResult.failure("Connector not active or no vault available", 0);
        }
        try {
            status = ConnectorStatus.SYNCING;
            lastError = null;
            var result = doSyncAll();
            if (result.success()) {
                consecutiveErrors = 0;
                lastSyncTimestamp = System.currentTimeMillis();
                status = ConnectorStatus.IDLE;
            } else {
                consecutiveErrors++;
                lastError = result.summary();
                status = ConnectorStatus.ERROR;
            }
            return result;
        } catch (Exception e) {
            consecutiveErrors++;
            String msg = "Sync failed: " + e.getMessage();
            log.errorf("[%s] %s", id(), msg);
            lastError = msg;
            status = ConnectorStatus.ERROR;
            return SyncResult.failure(msg, 0);
        } finally {
            if (status != ConnectorStatus.ERROR && lastSyncTimestamp > 0) {
                status = ConnectorStatus.IDLE;
            }
        }
    }

    /** Subclass implements the actual sync logic here. */
    protected abstract SyncResult doSyncAll();

    /** Resolve a secret value from the vault for this connector. */
    protected Optional<String> resolveSecret(String key) {
        if (vault == null || !vault.isEnabled()) {
            log.warnf("[%s] Vault not available — cannot resolve secret '%s'", id(), key);
            return Optional.empty();
        }
        String fullKey = connectorSecretKey(key);
        return vault.get(fullKey);
    }

    /** Build the vault key prefix for this connector type. */
    protected String connectorSecretPrefix() {
        return "connector." + id() + ".";
    }

    protected String connectorSecretKey(String subKey) {
        return connectorSecretPrefix() + subKey;
    }

    /** Get a config value set during initialization. */
    @SuppressWarnings("unchecked")
    protected <T> T getConfig(String key, T defaultValue) {
        return (T) config.computeIfAbsent(key, k -> defaultValue);
    }

    protected void putConfig(String key, Object value) {
        config.put(key, value);
    }

    protected Map<String, Object> getAllConfig() {
        return Map.copyOf(config);
    }

    /** Mask a value for safe logging before sending over WebSocket/error messages. */
    protected String mask(String s) {
        return SecretsProvider.mask(s);
    }

    /** Check if a string looks like sensitive content. */
    protected boolean looksSensitive(String s) {
        return SecretsProvider.looksSensitive(s);
    }
}