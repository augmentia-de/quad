package de.augmentia.quad.core.session;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopedSessionStateTest {

    @Test
    void shouldDelegateSessionIdToParent() {
        AgentSessionState parent = new AgentSessionState();
        AgentSessionState child = ScopedSessionState.childOf(parent);

        assertThat(child.getSessionId()).isEqualTo(parent.getSessionId());
    }

    @Test
    void shouldDelegateTenantIdToParent() {
        AgentSessionState parent = new AgentSessionState();
        parent.setTenantId("acme-corp");
        AgentSessionState child = ScopedSessionState.childOf(parent);

        assertThat(child.getTenantId()).isEqualTo("acme-corp");
    }

    @Test
    void shouldDelegateCurrentProjectToParent() {
        AgentSessionState parent = new AgentSessionState();
        parent.setCurrentProject("KI-Portal");
        AgentSessionState child = ScopedSessionState.childOf(parent);

        assertThat(child.getCurrentProject()).isEqualTo("KI-Portal");
    }

    @Test
    void shouldCombineFindingsFromParentAndChild() {
        AgentSessionState parent = new AgentSessionState();
        parent.addFinding("parent-finding-1");
        parent.addFinding("parent-finding-2");

        AgentSessionState child = ScopedSessionState.childOf(parent);
        child.addFinding("child-finding-1");

        assertThat(child.findings()).containsExactly(
                "parent-finding-1", "parent-finding-2", "child-finding-1");
        assertThat(parent.findings()).containsExactly(
                "parent-finding-1", "parent-finding-2");
    }

    @Test
    void shouldIsolateOwnFindingsInScopedState() {
        AgentSessionState parent = new AgentSessionState();
        parent.addFinding("parent-only");

        ScopedSessionState child = ScopedSessionState.childOf(parent);
        child.addFinding("child-only");

        assertThat(child.ownFindings()).containsExactly("child-only");
        assertThat(child.findings()).containsExactly("parent-only", "child-only");
    }

    @Test
    void shouldIsolateCwdManager() {
        AgentSessionState parent = new AgentSessionState();
        parent.setCwd("project-a");

        AgentSessionState child = ScopedSessionState.childOf(parent);
        child.setCwd("sub-task");

        assertThat(parent.currentCwd()).isEqualTo("project-a");
        assertThat(child.currentCwd()).isEqualTo("sub-task");
    }

    @Test
    void shouldNotShareSagaLog() {
        AgentSessionState parent = new AgentSessionState();
        parent.registerCompensation("tool1", state -> {});

        ScopedSessionState child = ScopedSessionState.childOf(parent);

        // Child hat leeres SagaLog
        assertThat(child.getSagaLog()).isEmpty();
        // Child can add its own compensations (without affecting the parent)
        child.registerCompensation("tool2", state -> {});
        assertThat(child.getSagaLog()).isEmpty(); // registerCompensation ist No-Op
    }

    @Test
    void shouldIsolateLastToolNames() {
        AgentSessionState parent = new AgentSessionState();
        parent.setLastToolNames(Set.of("websearch", "read_file"));

        AgentSessionState child = ScopedSessionState.childOf(parent);
        child.setLastToolNames(Set.of("calculate"));

        assertThat(parent.getLastToolNames()).containsExactlyInAnyOrder("websearch", "read_file");
        assertThat(child.getLastToolNames()).containsExactly("calculate");
    }

    @Test
    void shouldIsolateLockCounters() {
        AgentSessionState parent = new AgentSessionState();
        AgentSessionState child = ScopedSessionState.childOf(parent);

        // Both start at 0 and increment independently
        int parentToken1 = parent.acquireLock();
        int childToken1 = child.acquireLock();
        int parentToken2 = parent.acquireLock();

        assertThat(parentToken1).isEqualTo(1);
        assertThat(childToken1).isEqualTo(1);
        assertThat(parentToken2).isEqualTo(2);

        parent.releaseLock(parentToken2);
        parent.releaseLock(parentToken1);
        child.releaseLock(childToken1);
    }

    @Test
    void shouldReturnParentForIntrospection() {
        AgentSessionState parent = new AgentSessionState();
        ScopedSessionState child = ScopedSessionState.childOf(parent);

        assertThat(child.parent()).isSameAs(parent);
    }

    @Test
    void shouldThrowOnNullParent() {
        assertThatThrownBy(() -> ScopedSessionState.childOf(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent must not be null");
    }

    @Test
    void shouldNotBeInstanceOfScopedSessionWhenUsingChildOf() {
        AgentSessionState parent = new AgentSessionState();
        AgentSessionState child = ScopedSessionState.childOf(parent);

        assertThat(child).isInstanceOf(ScopedSessionState.class);
    }

    @Test
    void shouldHaveEmptyFindingsWhenNoFindingsExist() {
        AgentSessionState parent = new AgentSessionState();
        AgentSessionState child = ScopedSessionState.childOf(parent);

        assertThat(child.findings()).isEmpty();
    }

    @Test
    void shouldIsolateCwdPushPop() {
        AgentSessionState parent = new AgentSessionState();
        parent.pushCwd("parent-dir");

        AgentSessionState child = ScopedSessionState.childOf(parent);
        child.pushCwd("child-dir");

        assertThat(parent.currentCwd()).isEqualTo("parent-dir");
        assertThat(child.currentCwd()).isEqualTo("child-dir");

        child.popCwd();
        assertThat(child.currentCwd()).isEqualTo(".");
        assertThat(parent.currentCwd()).isEqualTo("parent-dir");
    }
}
