package de.augmentia.quad.core.connectors;

import de.augmentia.quad.core.security.SecretsProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConnectorRegistryTest {

    /** Enabled in-memory vault stub so BaseConnector.syncAll passes its guard. */
    private static final SecretsProvider STUB_VAULT = new SecretsProvider() {
        @Override public Optional<String> get(String key) { return Optional.empty(); }
        @Override public void put(String key, String value) {}
        @Override public boolean isEnabled() { return true; }
    };

    @Test
    void registerAndList() {
        ConnectorRegistry registry = new ConnectorRegistry();
        MockConnector conn = new MockConnector("github");
        registry.register(conn);

        assertEquals(1, registry.size());
        assertTrue(registry.getById("github").isPresent());
        List<Connector> list = registry.listAll();
        assertEquals(1, list.size());
        assertEquals("github", list.get(0).id());
    }

    @Test
    void duplicateRegistrationThrows() {
        ConnectorRegistry registry = new ConnectorRegistry();
        registry.register(new MockConnector("github"));
        assertThrows(IllegalArgumentException.class, () -> 
            registry.register(new MockConnector("github")));
    }

    @Test
    void unregisterRemovesAndDestroys() {
        ConnectorRegistry registry = new ConnectorRegistry();
        MockConnector conn = new MockConnector("slack");
        registry.register(conn);
        
        assertTrue(registry.unregister("slack"));
        assertFalse(registry.getById("slack").isPresent());
        assertTrue(conn.wasDestroyed); // Verify destroy was called
    }

    @Test
    void activeConnectorReturnedCorrectly() {
        ConnectorRegistry registry = new ConnectorRegistry();
        Connector active = new MockConnector("github", true);
        Connector inactive = new MockConnector("slack", false);
        registry.register(active);
        registry.register(inactive);

        assertTrue(registry.getActiveById("github").isPresent());
        assertFalse(registry.getActiveById("slack").isPresent());

        List<Connector> activeList = registry.listActive();
        assertEquals(1, activeList.size());
        assertEquals("github", activeList.get(0).id());
    }

    @Test
    void syncAllReturnsResults() {
        ConnectorRegistry registry = new ConnectorRegistry();
        MockConnector c1 = new MockConnector("c1", true, true);
        MockConnector c2 = new MockConnector("c2", true, false);
        registry.register(c1);
        registry.register(c2);

        var results = registry.syncAllConnectors();
        assertEquals(2, results.size());
        assertFalse(results.get("c1").success());
        assertTrue(results.get("c2").success());
    }

    @Test
    void syncConnectorBySpecificId() {
        ConnectorRegistry registry = new ConnectorRegistry();
        registry.register(new MockConnector("github", true));
        registry.register(new MockConnector("slack", false));

        var result = registry.syncConnector("github");
        assertTrue(result.isPresent());
        assertTrue(result.get().success());

        // Inactive connector returns empty
        var slackResult = registry.syncConnector("slack");
        assertFalse(slackResult.isPresent());
    }

    @Test
    void statusSnapshotWorks() {
        ConnectorRegistry registry = new ConnectorRegistry();
        MockConnector c1 = new MockConnector("c1", true);
        registry.register(c1);
        c1.triggerError();

        Map<String, ConnectorStatus> statuses = registry.getStatuses();
        assertEquals(1, statuses.size());
        assertEquals(ConnectorStatus.ERROR, statuses.get("c1"));
    }

    @Test
    void initAllCallsInitOnEachConnector() {
        ConnectorRegistry registry = new ConnectorRegistry();
        MockConnector c1 = new MockConnector("c1");
        MockConnector c2 = new MockConnector("c2");
        registry.register(c1);
        registry.register(c2);

        registry.initAll();

        assertTrue(c1.initCalled);
        assertTrue(c2.initCalled);
    }

    private static class MockConnector extends BaseConnector {
        final String id;
        boolean wasDestroyed;
        boolean initCalled;
        boolean shouldSyncFail;

        MockConnector(String id) { this(id, false, false); }
        MockConnector(String id, boolean active) { this(id, active, false); }
        MockConnector(String id, boolean active, boolean shouldSyncFail) {
            super(STUB_VAULT);
            this.id = id;
            this.active = active;
            this.shouldSyncFail = shouldSyncFail;
        }

        @Override public String id() { return id; }
        @Override public String name() { return id + " connector"; }
        @Override public String description() { return "Mock connector " + id; }
        @Override public EventType[] supportedEvents() { return new EventType[]{EventType.INCOMING}; }
        @Override public void init() { initCalled = true; }
        @Override public void destroy() { wasDestroyed = true; }
        @Override protected SyncResult doSyncAll() {
            if (shouldSyncFail) {
                triggerError();
                return SyncResult.failure("simulated failure", 0);
            }
            return SyncResult.success(5, "mocked items processed", 100);
        }
        public void triggerError() { lastError = "test error"; status = ConnectorStatus.ERROR; consecutiveErrors++; }
    }
}