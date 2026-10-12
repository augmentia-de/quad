package de.augmentia.quad.core.config;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ModelHeaders#current()} resolves the session header from the active
 * {@link CurrentSession}, so {@code QUAD_LLM_SESSION_ID} is variable per session (and only used as
 * fallback when no session is active).
 */
class ModelHeadersTest {

    // Use the highest-precedence keys (vault.*) so tests are independent of the shell environment.
    private static final String[] KEYS = {
            "QUAD_LLM_USER_AGENT", "QUAD_LLM_SESSION_ID", "QUAD_LLM_SESSION_HEADER", "QUAD_LLM_HEADERS"
    };

    @AfterEach
    void cleanup() {
        CurrentSession.setCurrent(null);
        for (String key : KEYS) {
            System.clearProperty(key);
            System.clearProperty("vault." + key);
        }
    }

    private static void set(String key, String value) {
        System.setProperty("vault." + key, value);
    }

    @Test
    void emptyWhenNothingConfiguredAndNoSession() {
        assertThat(ModelHeaders.current()).isEmpty();
    }

    @Test
    void staticSessionIdUsedAsFallbackWhenNoSessionActive() {
        set("QUAD_LLM_SESSION_ID", "ses-static");
        assertThat(ModelHeaders.current())
                .containsEntry(ModelHeaders.DEFAULT_SESSION_HEADER, "ses-static");
    }

    @Test
    void activeSessionOverridesStaticSessionId() {
        set("QUAD_LLM_USER_AGENT", "agentflow-local/1.0");
        set("QUAD_LLM_SESSION_ID", "ses-static");
        CurrentSession.setCurrent(AgentSessionState.create("run-abc-123"));

        Map<String, String> headers = ModelHeaders.current();
        assertThat(headers).containsEntry("User-Agent", "agentflow-local/1.0");
        assertThat(headers).containsEntry(ModelHeaders.DEFAULT_SESSION_HEADER, "run-abc-123");
    }

    @Test
    void activeSessionAddsSessionHeaderEvenWithoutStaticConfig() {
        CurrentSession.setCurrent(AgentSessionState.create("run-xyz"));
        assertThat(ModelHeaders.current())
                .containsEntry(ModelHeaders.DEFAULT_SESSION_HEADER, "run-xyz");
    }

    @Test
    void sessionHeaderVariesPerSession() {
        CurrentSession.setCurrent(AgentSessionState.create("session-1"));
        String first = ModelHeaders.current().get(ModelHeaders.DEFAULT_SESSION_HEADER);

        CurrentSession.setCurrent(AgentSessionState.create("session-2"));
        String second = ModelHeaders.current().get(ModelHeaders.DEFAULT_SESSION_HEADER);

        assertThat(first).isEqualTo("session-1");
        assertThat(second).isEqualTo("session-2");
    }

    @Test
    void customSessionHeaderNameIsRespected() {
        set("QUAD_LLM_SESSION_HEADER", "x-session");
        CurrentSession.setCurrent(AgentSessionState.create("run-42"));
        assertThat(ModelHeaders.current()).containsEntry("x-session", "run-42");
    }
}
