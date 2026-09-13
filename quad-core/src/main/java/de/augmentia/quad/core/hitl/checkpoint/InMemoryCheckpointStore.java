package de.augmentia.quad.core.hitl.checkpoint;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryCheckpointStore implements CheckpointStore {

    private final Map<String, Checkpoint> checkpoints = new ConcurrentHashMap<>();

    @Override
    public void save(Checkpoint checkpoint) {
        checkpoints.put(checkpoint.id(), checkpoint);
    }

    @Override
    public Optional<Checkpoint> load(String checkpointId) {
        return Optional.ofNullable(checkpoints.get(checkpointId));
    }

    @Override
    public void updateStatus(String checkpointId, Checkpoint.Status status, String feedback) {
        var cp = checkpoints.get(checkpointId);
        if (cp != null) {
            if (status == Checkpoint.Status.APPROVED) {
                cp.approve(feedback);
            } else if (status == Checkpoint.Status.REJECTED) {
                cp.reject(feedback);
            }
        }
    }

    @Override
    public List<Checkpoint> findPending(String sessionId) {
        return checkpoints.values().stream()
            .filter(cp -> cp.sessionId().equals(sessionId) && cp.status() == Checkpoint.Status.PENDING)
            .toList();
    }

    @Override
    public List<Checkpoint> findAllPending() {
        return checkpoints.values().stream()
            .filter(cp -> cp.status() == Checkpoint.Status.PENDING)
            .toList();
    }
}
