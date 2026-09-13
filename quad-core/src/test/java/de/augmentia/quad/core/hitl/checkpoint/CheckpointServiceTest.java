package de.augmentia.quad.core.hitl.checkpoint;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointServiceTest {

    private final List<String> uiNotified = new ArrayList<>();
    private final List<String> asyncNotified = new ArrayList<>();
    private final List<String> legacyNotified = new ArrayList<>();

    private final CheckpointChannel uiChannel = cp -> uiNotified.add(cp.sessionId());
    private final CheckpointChannel asyncChannel = cp -> asyncNotified.add(cp.sessionId());
    private final CheckpointChannel legacyChannel = cp -> legacyNotified.add(cp.sessionId());

    @Test
    void requiresApproval_matchesToolsCaseInsensitive() {
        var svc = new CheckpointService("executeBash,writeFile", 1000);
        assertTrue(svc.requiresApproval("executeBash"));
        assertTrue(svc.requiresApproval("EXECUTEBASH"));
        assertTrue(svc.requiresApproval("  writeFile "));
        assertFalse(svc.requiresApproval("readFile"));
    }

    @Test
    void requiresApproval_disabledWhenHitlDisabled() {
        var svc = new CheckpointService("executeBash", 1000);
        svc.setHitlEnabled(false);
        assertFalse(svc.requiresApproval("executeBash"));
    }

    @Test
    void uiConnected_routesOnlyToUiChannels() {
        var svc = serviceWithUiProvider("session-1", true);
        svc.registerUiChannel(uiChannel);
        svc.registerAsyncChannel(asyncChannel);

        svc.createCheckpoint("session-1", "executeBash", "{}");

        assertTrue(uiNotified.contains("session-1"), "UI channel must be notified when UI is connected");
        assertTrue(asyncNotified.isEmpty(), "Async channel must NOT be notified when UI is connected");
    }

    @Test
    void uiDisconnected_fallsBackToAsyncChannels() {
        var svc = serviceWithUiProvider("session-1", false);
        svc.registerUiChannel(uiChannel);
        svc.registerAsyncChannel(asyncChannel);

        svc.createCheckpoint("session-1", "executeBash", "{}");

        assertTrue(uiNotified.isEmpty(), "UI channel must NOT be notified when UI is not connected");
        assertTrue(asyncNotified.contains("session-1"), "Async channel must be notified as fallback");
    }

    @Test
    void noUiChannels_registered_legacyRoutingNotifiesAllRegisteredChannels() {
        var svc = serviceWithUiProvider(null, true);
        svc.registerChannel(legacyChannel);
        svc.registerChannel(asyncChannel);

        svc.createCheckpoint("session-1", "executeBash", "{}");

        assertTrue(legacyNotified.contains("session-1"));
        assertTrue(asyncNotified.contains("session-1"));
    }

    @Test
    void uiConnected_butNoUiChannelConfigured_asyncActsAsSafetyNet() {
        var svc = serviceWithUiProvider("session-1", true);
        svc.registerAsyncChannel(asyncChannel);

        svc.createCheckpoint("session-1", "executeBash", "{}");

        assertTrue(asyncNotified.contains("session-1"),
            "Without a configured UI channel, async channels must receive the checkpoint");
    }

    @Test
    void uiProviderSessionScoped_routesPerSession() {
        var svc = serviceWithUiProvider("connected-session", true);
        svc.registerUiChannel(uiChannel);
        svc.registerAsyncChannel(asyncChannel);

        svc.createCheckpoint("disconnected-session", "executeBash", "{}");

        assertTrue(uiNotified.isEmpty(), "Only the connected session should be UI-routed");
        assertTrue(asyncNotified.contains("disconnected-session"));
    }

    private CheckpointService serviceWithUiProvider(String uiConnectedSessionId, boolean connected) {
        var svc = new CheckpointService("executeBash", 1000);
        if (uiConnectedSessionId != null) {
            UiConnectionProvider provider = new UiConnectionProvider() {
                @Override
                public boolean isUiConnected(String sessionId) {
                    return connected && uiConnectedSessionId.equals(sessionId);
                }
            };
            svc.setUiConnectionProvider(provider);
        }
        return svc;
    }
}