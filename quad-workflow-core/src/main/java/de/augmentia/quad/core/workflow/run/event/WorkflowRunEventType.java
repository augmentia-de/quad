package de.augmentia.quad.core.workflow.run.event;

/**
 * Types of immutable run events (append-only run log).
 *
 * <p>HITL follows one state machine across both mechanisms (explicit {@code hitl} node and
 * {@code requiresHumanApproval} sugar): {@code WAITING_HITL} → {@code APPROVED} /
 * {@code REJECTED}, with {@code EXPIRED} and {@code ESCALATED} as additional terminal
 * outcomes.</p>
 */
public enum WorkflowRunEventType {
    RUN_STARTED,
    NODE_STARTED,
    NODE_COMPLETED,
    NODE_FAILED,
    /** A step entered the human-approval gate. */
    WAITING_HITL,
    APPROVED,
    REJECTED,
    EXPIRED,
    ESCALATED,
    /** Run-time override was applied (model/temperature/prompt for this run only). */
    OVERRIDES_APPLIED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_CANCELLED
}
