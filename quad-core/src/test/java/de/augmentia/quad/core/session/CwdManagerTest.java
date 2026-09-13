package de.augmentia.quad.core.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CwdManagerTest {

    @Test
    void defaultsToRoot() {
        CwdManager cwd = new CwdManager();
        assertThat(cwd.current()).isEqualTo(".");
        assertThat(cwd.root()).isEqualTo(".");
        assertThat(cwd.depth()).isZero();
        assertThat(cwd.isRoot()).isTrue();
    }

    @Test
    void pushAndPopMaintainParentContext() {
        CwdManager cwd = new CwdManager();
        cwd.push("backend");
        assertThat(cwd.current()).isEqualTo("backend");
        assertThat(cwd.depth()).isEqualTo(1);
        assertThat(cwd.isRoot()).isFalse();

        cwd.pop();
        assertThat(cwd.current()).isEqualTo(".");
        assertThat(cwd.depth()).isZero();
        assertThat(cwd.isRoot()).isTrue();
    }

    @Test
    void nestedPushes() {
        CwdManager cwd = new CwdManager();
        cwd.push("backend").push("src/main/java");
        assertThat(cwd.current()).isEqualTo("src/main/java");
        assertThat(cwd.depth()).isEqualTo(2);
        cwd.pop();
        assertThat(cwd.current()).isEqualTo("backend");
        cwd.pop();
        assertThat(cwd.current()).isEqualTo(".");
    }

    @Test
    void rootCannotBePopped() {
        CwdManager cwd = new CwdManager();
        cwd.pop();
        assertThat(cwd.current()).isEqualTo(".");
        assertThat(cwd.depth()).isZero();
    }

    @Test
    void resetRestoresRoot() {
        CwdManager cwd = new CwdManager();
        cwd.push("a").push("b");
        cwd.reset();
        assertThat(cwd.current()).isEqualTo(".");
        assertThat(cwd.depth()).isZero();
        assertThat(cwd.isRoot()).isTrue();
    }

    @Test
    void customRoot() {
        CwdManager cwd = new CwdManager("/work/base");
        assertThat(cwd.current()).isEqualTo("/work/base");
        assertThat(cwd.root()).isEqualTo("/work/base");
    }

    @Test
    void rejectsBlankPush() {
        CwdManager cwd = new CwdManager();
        assertThatThrownBy(() -> cwd.push("  "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cwd.push(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
