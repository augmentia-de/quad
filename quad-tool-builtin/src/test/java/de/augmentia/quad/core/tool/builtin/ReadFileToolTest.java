package de.augmentia.quad.core.tool.builtin;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
import de.augmentia.quad.core.session.WorkspaceResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class ReadFileToolTest {

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
    void shouldReadFileContent() throws IOException {
        Path testFile = tempDir.resolve("test-session").resolve("test.txt");
        Files.writeString(testFile, "Line 1\nLine 2\nLine 3\n");

        String result = readFileTool.readFile("test.txt", null, null);

        assertThat(result).contains("--- File: test.txt");
        assertThat(result).contains("1 | Line 1");
        assertThat(result).contains("2 | Line 2");
        assertThat(result).contains("3 | Line 3");
    }

    @Test
    void shouldReadWithOffsetAndLimit() throws IOException {
        Path testFile = tempDir.resolve("test-session").resolve("lines.txt");
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            content.append("Line ").append(i).append("\n");
        }
        Files.writeString(testFile, content.toString());

        String result = readFileTool.readFile("lines.txt", 3, 4);

        assertThat(result).contains("3 | Line 3");
        assertThat(result).contains("4 | Line 4");
        assertThat(result).contains("5 | Line 5");
        assertThat(result).contains("6 | Line 6");
    }

    @Test
    void shouldReturnErrorForNonExistentFile() {
        String result = readFileTool.readFile("nonexistent.txt", null, null);

        assertThat(result).contains("Error: File not found");
    }

    @Test
    void shouldReturnAccessDeniedForPathTraversal() {
        String result = readFileTool.readFile("../other-session/secret.txt", null, null);

        assertThat(result).contains("Access denied");
        assertThat(result).contains("outside the session workspace");
    }

    @Test
    void shouldReturnErrorForDirectory() throws IOException {
        Path dir = tempDir.resolve("test-session").resolve("emptydir");
        Files.createDirectories(dir);

        String result = readFileTool.readFile("emptydir", null, null);

        assertThat(result).contains("Error: Path");
        assertThat(result).contains("is a directory");
    }

    @Test
    void shouldHandleNoSession() {
        CurrentSession.setCurrent(null);

        String result = readFileTool.readFile("test.txt", null, null);

        assertThat(result).contains("Error: No active session found");
    }

    @Test
    void shouldReadRelativeToCwd() throws IOException {
        Files.createDirectories(tempDir.resolve("test-session/backend"));
        Path testFile = tempDir.resolve("test-session/backend/app.txt");
        Files.writeString(testFile, "app content");

        testSessionState.pushCwd("backend");
        String result = readFileTool.readFile("app.txt", null, null);

        assertThat(result).contains("--- File: app.txt");
        assertThat(result).contains("app content");
    }

    @Test
    void shouldRestoreRootAfterPopCwd() throws IOException {
        Path rootFile = tempDir.resolve("test-session/root.txt");
        Files.writeString(rootFile, "root content");

        testSessionState.pushCwd("backend");
        testSessionState.popCwd();

        String result = readFileTool.readFile("root.txt", null, null);

        assertThat(result).contains("root content");
    }

    @Test
    void shouldRejectTraversalOutOfCwd() throws IOException {
        Files.createDirectories(tempDir.resolve("test-session/backend"));

        testSessionState.pushCwd("backend");
        String result = readFileTool.readFile("../../root.txt", null, null);

        assertThat(result).contains("Access denied");
        assertThat(result).contains("outside the session workspace");
    }
}