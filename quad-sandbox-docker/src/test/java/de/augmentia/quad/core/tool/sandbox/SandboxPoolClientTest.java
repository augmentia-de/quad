package de.augmentia.quad.core.tool.sandbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SandboxPoolClientTest {

    @Test
    void returnsStubWhenDisabled() {
        SandboxPoolClient pool = new SandboxPoolClient(
                SandboxConfig.of(false, "img", "512m", 1000, true));
        String result = pool.run("echo hi", "s1");
        assertThat(result).contains("[sandbox disabled]");
    }

    @Test
    void resolvesDefaultWorkspaceForNullSession() throws Exception {
        SandboxPoolClient pool = new SandboxPoolClient(
                SandboxConfig.of(true, "img", "512m", 60000, true));
        var method = SandboxPoolClient.class.getDeclaredMethod("workspaceDir", String.class);
        method.setAccessible(true);
        java.nio.file.Path dir = (java.nio.file.Path) method.invoke(pool, (Object) null);
        assertThat(dir.toString()).endsWith(java.nio.file.Path.of("/default").toString());
    }
}
