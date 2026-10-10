package de.augmentia.quad.core.workflow.run.event;

import java.util.List;

/**
 * Append-only store for run events. The event log is the source of truth for a run's state;
 * current state is a projection over it ({@link WorkflowRunProjection}).
 *
 * <p>Implementations assign {@code sequence} monotonically and return events ordered by it.</p>
 */
public interface WorkflowRunEventStore {

    /** Appends an event, assigning its sequence; returns the stored event. */
    WorkflowRunEvent append(WorkflowRunEvent event);

    /** All events of a run, ordered by sequence. */
    List<WorkflowRunEvent> events(String runId);

    /** Projects the current run state from its events. */
    default WorkflowRunProjection project(String runId) {
        return WorkflowRunProjection.of(runId, events(runId));
    }
}
