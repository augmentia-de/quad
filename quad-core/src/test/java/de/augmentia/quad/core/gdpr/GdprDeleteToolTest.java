package de.augmentia.quad.core.gdpr;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.SessionManager;
import de.augmentia.quad.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GdprDeleteToolTest {

    private SessionManager sessionManager;
    private GdprDeleteTool tool;
    private AgentSessionState state;

    @BeforeEach
    void setUp() {
        sessionManager = mock(SessionManager.class);
        tool = new GdprDeleteTool(sessionManager);
        state = new AgentSessionState();
        state.setTenantId("tenant-123");
    }

    @Test
    void shouldReturnToolSpecification() {
        var spec = tool.spec();
        assertNotNull(spec);
        assertEquals("gdpr_delete", spec.name());
        assertTrue(spec.parameters().properties().containsKey("sessionId"));
        assertTrue(spec.parameters().required().contains("sessionId"));
    }

    @Test
    void shouldDeleteSessionSuccessfully() throws Exception {
        String jsonArguments = "{\"sessionId\":\"session-456\"}";
        when(sessionManager.getSession("session-456")).thenReturn(state);

        ToolResult result = tool.execute(jsonArguments, state);

        assertTrue(result.text().contains("session-456"));
        assertTrue(result.text().contains("DELETED"));
        verify(sessionManager).removeSession("session-456");
    }

    @Test
    void shouldReturnErrorWhenSessionNotFound() throws Exception {
        String jsonArguments = "{\"sessionId\":\"nonexistent\"}";
        when(sessionManager.getSession("nonexistent")).thenReturn(null);

        ToolResult result = tool.execute(jsonArguments, state);

        assertTrue(result.text().contains("not found"));
    }

    @Test
    void shouldParseJsonWithWhitespaceAndQuotes() throws Exception {
        String jsonArguments = "{\"sessionId\":\"  session-with-spaces  \"}";

        AgentSessionState sessionState = new AgentSessionState();
        sessionState.setTenantId("tenant-789");
        when(sessionManager.getSession("  session-with-spaces  ")).thenReturn(sessionState);

        ToolResult result = tool.execute(jsonArguments, state);

        assertTrue(result.text().contains("session-with-spaces"));
    }

    @Test
    void shouldHandleEmptySessionId() throws Exception {
        String jsonArguments = "{}";

        Exception exception = assertThrows(RuntimeException.class, () -> {
            tool.execute(jsonArguments, state);
        });

        assertTrue(exception.getMessage().contains("erforderlicher Parameter"));
    }

    @Test
    void shouldHandleNullArguments() throws Exception {
        Exception exception = assertThrows(RuntimeException.class, () -> {
            tool.execute(null, state);
        });
    }
}