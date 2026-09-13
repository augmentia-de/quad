package de.augmentia.quad.core.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class GrantedDirectoryTest {

    @TempDir
    Path tempDir;

    AgentSessionState state;
    WorkspaceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        state = new AgentSessionState();
        Field f = AgentSessionState.class.getDeclaredField("sessionId");
        f.setAccessible(true);
        f.set(state, "sess-1");
        Files.createDirectories(tempDir.resolve("sess-1"));

        resolver = new WorkspaceResolver() {
            @Override
            public Path sessionDir(String sessionId) {
                return tempDir.resolve(sessionId);
            }
        };
    }

    @Test
    void sessionRootAlwaysReadableAndWritable() {
        Path p = resolver.resolveForRead(state, "file.txt");
        assertThat(p).isEqualTo(tempDir.resolve("sess-1").resolve("file.txt"));
        Path w = resolver.resolveForWrite(state, "out.txt");
        assertThat(w).isEqualTo(tempDir.resolve("sess-1").resolve("out.txt"));
    }

    @Test
    void nonGrantedPathRejected() {
        assertThatThrownBy(() -> resolver.resolveForRead(state, "../../etc/passwd"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Access denied");
    }

    @Test
    void grantedReadOnlyAllowsReadButNotWrite() throws Exception {
        Path grant = tempDir.resolve("extern");
        Files.createDirectories(grant);

        state.grantDir(grant, Access.READ);

        Path resolved = resolver.resolveForRead(state, grant.toString());
        assertThat(resolved).isEqualTo(grant);
        assertThatThrownBy(() -> resolver.resolveForWrite(state, grant.toString()))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("not writable");
    }

    @Test
    void grantedReadWriteAllowsWrite() throws Exception {
        Path grant = tempDir.resolve("extern");
        Files.createDirectories(grant);

        state.grantDir(grant, Access.READ_WRITE);

        Path w = resolver.resolveForWrite(state, grant.resolve("f.txt").toString());
        assertThat(w).isEqualTo(grant.resolve("f.txt"));
    }

    @Test
    void revokeRemovesGrant() throws Exception {
        Path grant = tempDir.resolve("extern");
        Files.createDirectories(grant);

        state.grantDir(grant, Access.READ);
        assertThat(state.isGranted(grant)).isTrue();

        assertThat(state.revokeDir(grant)).isTrue();
        assertThat(state.isGranted(grant)).isFalse();
        assertThatThrownBy(() -> resolver.resolveForRead(state, grant.toString()))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void grantedDirectoriesIsImmutableCopy() throws Exception {
        Path grant = tempDir.resolve("extern");
        Files.createDirectories(grant);
        state.grantDir(grant, Access.READ);

        var copy = state.grantedDirectories();
        assertThatThrownBy(() -> copy.add(new GrantedDirectory(grant, Access.READ)))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
