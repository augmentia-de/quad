package de.augmentia.quad.core.tool.builtin;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.Access;
import de.augmentia.quad.core.session.CurrentSession;
import de.augmentia.quad.core.session.WorkspaceResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class WriteToolTest {

    @TempDir
    Path tempDir;

    WriteTool writeTool;
    AgentSessionState testSessionState;

    @BeforeEach
    void setUp() throws Exception {
        writeTool = new WriteTool() {
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
        Field f = AgentSessionState.class.getDeclaredField("sessionId");
        f.setAccessible(true);
        f.set(testSessionState, "test-session");
        CurrentSession.setCurrent(testSessionState);
        Files.createDirectories(tempDir.resolve("test-session"));
    }

    @Test
    void writesWithinSessionRoot() {
        String result = writeTool.writeFile("hello.txt", "world");
        assertThat(result).contains("Successfully wrote 5 bytes to: hello.txt");
        assertThat(Files.exists(tempDir.resolve("test-session/hello.txt"))).isTrue();
    }

    @Test
    void rejectsWriteOutsideSessionRoot() {
        String result = writeTool.writeFile("/etc/evil.txt", "x");
        assertThat(result).contains("Access denied");
    }

    @Test
    void rejectsWriteToReadOnlyGrantedDir() throws Exception {
        Path grant = tempDir.resolve("readonly");
        Files.createDirectories(grant);
        testSessionState.grantDir(grant, Access.READ);

        String result = writeTool.writeFile(grant.resolve("f.txt").toString(), "x");
        assertThat(result).contains("Access denied");
        assertThat(result).contains("not writable");
    }

    @Test
    void allowsWriteToReadWriteGrantedDir() throws Exception {
        Path grant = tempDir.resolve("rw");
        Files.createDirectories(grant);
        testSessionState.grantDir(grant, Access.READ_WRITE);

        String result = writeTool.writeFile(grant.resolve("f.txt").toString(), "x");
        assertThat(result).contains("Successfully wrote 1 bytes");
        assertThat(Files.exists(grant.resolve("f.txt"))).isTrue();
    }
}
