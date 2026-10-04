package de.augmentia.quad.workflow.engine.port;

import java.util.Optional;

/**
 * Port for checkpoints with which a run is continued after a restart.
 *
 * <p>{@code RunStateWriter} and {@code WorkflowEngine} accessed
 * {@code StepSnapshotStore} directly. Without a port the engine would know
 * the persistence mechanism of the deployment — including table names and
 * the {@code Optional} signature.
 *
 * <p>Implementierung: {@code StepCheckpointAdapter} in quad-quarkus.
 */
public interface StepCheckpointPort {

    /**
     * Secures the progress of a run.
     *
     * @return {@code true} if the snapshot was written
     */
    boolean save(String runId, int stepIndex, String runStatus,
                 String snapshotJson, String memorySummary);

    /**
     * Reads the most recent snapshot of a run for resume/crash recovery.
     *
     * <p>If there was none, the result is {@link Optional#empty()} — not an error.
     */
    Optional<Checkpoint> loadLast(String runId);

    /** Part of a checkpoint; created by the adapter from the store snapshot. */
    record Checkpoint(String runId, int stepIndex, String stepStatus,
                      String environmentState, String memorySummary) {

        /** Resumable state for the engine; {@code null} if nothing was stored. */
        public String environment() {
            return environmentState;
        }
    }
}