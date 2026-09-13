package de.augmentia.quad.core.gdpr;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.SessionManager;
import de.augmentia.quad.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GdprExportToolTest {

    private SessionManager sessionManager;
    private GdprExportTool tool;

    @BeforeEach
    void setUp() {
        sessionManager = mock(SessionManager.class);
        tool = new GdprExportTool(sessionManager);
    }

    @Test
    void shouldReturnToolSpecification() {
        var spec = tool.spec();
        assertNotNull(spec);
        assertEquals("gdpr_export", spec.name());
    }

    @Test
    void shouldExportSessionSuccessfully() throws Exception {
        String jsonArguments = "{\"sessionId\":\"session-456\"}";
        AgentSessionState state = new AgentSessionState();
        when(sessionManager.getSession("session-456")).thenReturn(state);

        ToolResult result = tool.execute(jsonArguments, state);

        assertNotNull(result);
    }

    @Test
    void shouldExportWithFindings() throws Exception {
        String jsonArguments = "{\"sessionId\":\"session-456\"}";
        AgentSessionState sessionState = new AgentSessionState();
        sessionState.addFinding("Finding 1: Test finding");
        when(sessionManager.getSession("session-456")).thenReturn(sessionState);

        ToolResult result = tool.execute(jsonArguments, new AgentSessionState());

        assertNotNull(result);
    }

    @Test
    void shouldThrowWhenSessionNotFound() throws Exception {
        String jsonArguments = "{\"sessionId\":\"nonexistent\"}";
        when(sessionManager.getSession("nonexistent")).thenReturn(null);

        Exception ex = assertThrows(RuntimeException.class, () -> {
            tool.execute(jsonArguments, new AgentSessionState());
        });
        assertTrue(ex.getMessage().contains("nicht gefunden"));
    }

    @Test
    void shouldParseJsonCorrectly() throws Exception {
        String jsonArguments = "{\"sessionId\":\"my-session-id\"}";
        AgentSessionState sessionState = new AgentSessionState();
        when(sessionManager.getSession("my-session-id")).thenReturn(sessionState);

        ToolResult result = tool.execute(jsonArguments, new AgentSessionState());

        assertNotNull(result);
    }

    @Test
    void shouldHandleNullArguments() throws Exception {
        assertThrows(RuntimeException.class, () -> {
            tool.execute(null, new AgentSessionState());
        });
    }
}