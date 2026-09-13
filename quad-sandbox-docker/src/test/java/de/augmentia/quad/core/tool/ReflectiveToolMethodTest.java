package de.augmentia.quad.core.tool;

import de.augmentia.quad.core.agent.runtime.SandboxClient;
import de.augmentia.quad.core.tool.sandbox.BashSandboxTool;
import de.augmentia.quad.core.session.AgentSessionState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReflectiveToolMethodTest {

    @Test
    void shouldCreateToolMethodForExecuteBash() throws NoSuchMethodException {
        SandboxClient mockClient = mock(SandboxClient.class);
        BashSandboxTool bashTool = new BashSandboxTool(mockClient);

        Method method = BashSandboxTool.class.getMethod("executeBash", String.class);
        ToolArgsMapper argsMapper = new ToolArgsMapper(new com.fasterxml.jackson.databind.ObjectMapper());
        ToolMethod toolMethod = new ReflectiveToolMethod(method, bashTool, argsMapper);

        assertThat(toolMethod.spec().name()).isEqualTo("executeBash");
    }

    @Test
    void shouldExecuteToolMethod() throws Exception {
        SandboxClient mockClient = mock(SandboxClient.class);
        when(mockClient.run("echo test", "default")).thenReturn("test output");

        BashSandboxTool bashTool = new BashSandboxTool(mockClient);
        Method method = BashSandboxTool.class.getMethod("executeBash", String.class);
        ToolArgsMapper argsMapper = new ToolArgsMapper(new com.fasterxml.jackson.databind.ObjectMapper());
        ToolMethod toolMethod = new ReflectiveToolMethod(method, bashTool, argsMapper);

        AgentSessionState state = AgentSessionState.create("default");
        ToolResult result = toolMethod.execute("{\"script\": \"echo test\"}", state);

        assertThat(result.text()).isEqualTo("test output");
    }
}