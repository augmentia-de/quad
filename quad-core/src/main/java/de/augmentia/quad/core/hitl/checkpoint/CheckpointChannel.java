package de.augmentia.quad.core.hitl.checkpoint;

@FunctionalInterface
public interface CheckpointChannel {
    void notify(Checkpoint checkpoint);
}
