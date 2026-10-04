package de.augmentia.quad.quarkus.workflow.adapter;

import de.augmentia.quad.quarkus.persistence.StepSnapshotStore;
import de.augmentia.quad.workflow.engine.port.StepCheckpointPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Bindet die Engine an {@link StepSnapshotStore}.
 *
 * <p>Der Store liefert {@code StepSnapshotStore.Snapshot}; der Port liefert
 * {@link StepCheckpointPort.Checkpoint}. The translation is deliberately here and
 * not in the store: the engine should not know the store type, and the store
 * should not know who uses it.
 */
@ApplicationScoped
public class StepCheckpointAdapter implements StepCheckpointPort {

    private final StepSnapshotStore snapshotStore;

    @Inject
    public StepCheckpointAdapter(StepSnapshotStore snapshotStore) {
        this.snapshotStore = snapshotStore;
    }

    @Override
    public boolean save(String runId, int stepIndex, String runStatus,
                        String snapshotJson, String memorySummary) {
        return snapshotStore.save(runId, stepIndex, runStatus, snapshotJson, memorySummary);
    }

    @Override
    public Optional<Checkpoint> loadLast(String runId) {
        return snapshotStore.loadLast(runId).map(s -> new Checkpoint(
                s.runId(), s.stepIndex(), s.stepStatus(),
                s.environmentState(), s.memorySummary()));
    }
}