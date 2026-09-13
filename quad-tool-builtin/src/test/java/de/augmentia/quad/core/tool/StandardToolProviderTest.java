package de.augmentia.quad.core.tool;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
import de.augmentia.quad.core.session.WorkspaceResolver;
import de.augmentia.quad.core.tool.builtin.ReadFileTool;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class StandardToolProviderTest {

    @TempDir
    Path tempDir;

    ReadFileTool readFileTool;
    AgentSessionState testSessionState;

    @BeforeEach
    void setUp() throws Exception {
        readFileTool = new ReadFileTool() {
            @Override
            protected WorkspaceResolver getWorkspaceResolver() {
                return new WorkspaceResolver() {
                    @Override
                    public Path sessionDir(String sessionId) {
                        return tempDir.resolve(sessionId);
                    }
                };
            }
        };

        testSessionState = new AgentSessionState();
        Field sessionIdField = AgentSessionState.class.getDeclaredField("sessionId");
        sessionIdField.setAccessible(true);
        sessionIdField.set(testSessionState, "test-session");
        CurrentSession.setCurrent(testSessionState);

        Files.createDirectories(tempDir.resolve("test-session"));
    }

    @Test
    void shouldGetReadFileToolByName() {
        ReadFileTool localReadFileTool = new ReadFileTool() {
            @Override
            protected WorkspaceResolver getWorkspaceResolver() {
                return new WorkspaceResolver() {
                    @Override
                    public Path sessionDir(String sessionId) {
                        return tempDir.resolve(sessionId);
                    }
                };
            }
        };

        StandardToolProvider provider = new StandardToolProvider() {
            @Override
            public List<ToolMethod> getTools() {
                return List.of(createToolMethod(localReadFileTool, "readFile"));
            }

            @Override
            public String providerName() {
                return "test";
            }
        };

        java.util.Optional<ToolMethod> readFileToolResult = provider.getToolByName("readFile");

        assertThat(readFileToolResult).isPresent();
        assertThat(readFileToolResult.get().spec().name()).isEqualTo("readFile");
    }

    @Test
    void shouldUseFetchReadFileTool() throws Exception {
        Path testFile = tempDir.resolve("test-session").resolve("sample.txt");
        Files.createDirectories(tempDir.resolve("test-session"));
        Files.writeString(testFile, "sample content");

        ReadFileTool localReadFileTool = new ReadFileTool() {
            @Override
            protected WorkspaceResolver getWorkspaceResolver() {
                return new WorkspaceResolver() {
                    @Override
                    public Path sessionDir(String sessionId) {
                        return tempDir.resolve(sessionId);
                    }
                };
            }
        };

        testSessionState = new AgentSessionState();
        Field sessionIdField = AgentSessionState.class.getDeclaredField("sessionId");
        sessionIdField.setAccessible(true);
        sessionIdField.set(testSessionState, "test-session");
        CurrentSession.setCurrent(testSessionState);

        StandardToolProvider provider = new StandardToolProvider() {
            @Override
            public List<ToolMethod> getTools() {
                return List.of(createToolMethod(localReadFileTool, "readFile"));
            }

            @Override
            public String providerName() {
                return "test";
            }
        };

        java.util.Optional<ToolMethod> toolOpt = provider.getToolByName("readFile");
        assertThat(toolOpt).isPresent();

        ToolMethod tool = toolOpt.get();

        ToolResult result = tool.execute(
            "{\"path\": \"sample.txt\"}",
            testSessionState
        );

        assertThat(result.text()).contains("sample content");
    }

    private ToolMethod createToolMethod(Object beanInstance, String methodName) {
        for (java.lang.reflect.Method m : beanInstance.getClass().getMethods()) {
            if (m.getName().equals(methodName)) {
                return new ReflectiveToolMethod(m, beanInstance, new ToolArgsMapper(new com.fasterxml.jackson.databind.ObjectMapper()));
            }
        }
        throw new RuntimeException("Method not found: " + methodName);
    }
}