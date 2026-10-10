package de.augmentia.quad.core.workflow.run.event;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRunEventStoreTest {

    private final InMemoryWorkflowRunEventStore store = new InMemoryWorkflowRunEventStore();

    private WorkflowRunEvent event(WorkflowRunEventType type, String runId, String nodeId) {
        return WorkflowRunEvent.builder(type).runId(runId).node(nodeId).build();
    }

    @Test
    void appendAssignsMonotonicSequence() {
        store.append(event(WorkflowRunEventType.RUN_STARTED, "r1", null));
        store.append(event(WorkflowRunEventType.NODE_STARTED, "r1", "n1"));
        List<WorkflowRunEvent> events = store.events("r1");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).sequence()).isLessThan(events.get(1).sequence());
    }

    @Test
    void eventsAreIsolatedPerRun() {
        store.append(event(WorkflowRunEventType.RUN_STARTED, "r1", null));
        store.append(event(WorkflowRunEventType.RUN_STARTED, "r2", null));
        assertThat(store.events("r1")).hasSize(1);
        assertThat(store.events("r2")).hasSize(1);
        assertThat(store.events("unknown")).isEmpty();
    }

    @Test
    void projectionFoldsHitlApprovalFlow() {
        store.append(event(WorkflowRunEventType.RUN_STARTED, "r1", null));
        store.append(event(WorkflowRunEventType.NODE_STARTED, "r1", "review"));
        store.append(event(WorkflowRunEventType.WAITING_HITL, "r1", "review"));
        store.append(event(WorkflowRunEventType.APPROVED, "r1", "review"));
        store.append(event(WorkflowRunEventType.NODE_COMPLETED, "r1", "review"));
        store.append(event(WorkflowRunEventType.RUN_COMPLETED, "r1", null));

        WorkflowRunProjection p = store.project("r1");
        assertThat(p.status()).isEqualTo("completed");
        assertThat(p.statusOf("review")).isEqualTo("completed");
        assertThat(p.pendingApprovals()).isEmpty();
    }

    @Test
    void waitingHitlStaysPendingUntilResolved() {
        store.append(event(WorkflowRunEventType.RUN_STARTED, "r2", null));
        store.append(event(WorkflowRunEventType.WAITING_HITL, "r2", "gate"));
        assertThat(store.project("r2").pendingApprovals()).containsExactly("gate");
        store.append(event(WorkflowRunEventType.REJECTED, "r2", "gate"));
        WorkflowRunProjection p = store.project("r2");
        assertThat(p.pendingApprovals()).isEmpty();
        assertThat(p.statusOf("gate")).isEqualTo("rejected");
    }

    @Test
    void overridesAreRecorded() {
        store.append(event(WorkflowRunEventType.RUN_STARTED, "r3", null));
        store.append(WorkflowRunEvent.builder(WorkflowRunEventType.OVERRIDES_APPLIED)
                .runId("r3").reason("model=gpt-4o-mini,temperature=0.1").build());
        assertThat(store.project("r3").overrideNotes())
            .containsExactly("model=gpt-4o-mini,temperature=0.1");
    }

    @Test
    void projectionIsDeterministic() {
        store.append(event(WorkflowRunEventType.RUN_STARTED, "r4", null));
        store.append(event(WorkflowRunEventType.NODE_STARTED, "r4", "n1"));
        store.append(event(WorkflowRunEventType.NODE_COMPLETED, "r4", "n1"));
        store.append(event(WorkflowRunEventType.RUN_COMPLETED, "r4", null));
        WorkflowRunProjection a = store.project("r4");
        WorkflowRunProjection b = store.project("r4");
        assertThat(a.status()).isEqualTo(b.status());
        assertThat(a.nodeStatus()).isEqualTo(b.nodeStatus());
    }

    @Test
    void failedRunStatus() {
        store.append(event(WorkflowRunEventType.RUN_STARTED, "r5", null));
        store.append(event(WorkflowRunEventType.NODE_FAILED, "r5", "n1"));
        store.append(event(WorkflowRunEventType.RUN_FAILED, "r5", null));
        WorkflowRunProjection p = store.project("r5");
        assertThat(p.status()).isEqualTo("failed");
        assertThat(p.statusOf("n1")).isEqualTo("failed");
    }
}
