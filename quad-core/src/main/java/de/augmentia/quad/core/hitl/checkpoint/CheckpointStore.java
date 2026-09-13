package de.augmentia.quad.core.hitl.checkpoint;

import java.util.List;
import java.util.Optional;

public interface CheckpointStore {

    void save(Checkpoint checkpoint);

    Optional<Checkpoint> load(String checkpointId);

    void updateStatus(String checkpointId, Checkpoint.Status status, String feedback);

    List<Checkpoint> findPending(String sessionId);

    /** All pending checkpoints across all sessions */
    List<Checkpoint> findAllPending();
}
