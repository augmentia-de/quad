package de.augmentia.quad.core.tool.sandbox;

import de.augmentia.quad.core.agent.runtime.SandboxClient;
import de.augmentia.quad.core.session.CurrentSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BashSandboxToolTest {

    SandboxClient mockSandboxClient;
    BashSandboxTool bashTool;

    @BeforeEach
    void setUp() {
        CurrentSession.setCurrent(null);
        mockSandboxClient = mock(SandboxClient.class);
        bashTool = new BashSandboxTool(mockSandboxClient);
    }

    @Test
    void shouldExecuteBashCommand() {
        when(mockSandboxClient.run("echo 'hello'", "default")).thenReturn("hello");

        String result = bashTool.executeBash("echo 'hello'");

        assertThat(result).isEqualTo("hello");
        verify(mockSandboxClient).run("echo 'hello'", "default");
    }

    @Test
    void shouldHandleSandboxError() {
        when(mockSandboxClient.run(anyString(), anyString())).thenReturn("Error: Command failed");

        String result = bashTool.executeBash("invalid command");

        assertThat(result).isEqualTo("Error: Command failed");
    }

    @Test
    void shouldDelegateToSandboxWithDefaultSessionId() {
        when(mockSandboxClient.run("ls -la", "default")).thenReturn("total 0");

        String result = bashTool.executeBash("ls -la");

        verify(mockSandboxClient).run("ls -la", "default");
    }
}