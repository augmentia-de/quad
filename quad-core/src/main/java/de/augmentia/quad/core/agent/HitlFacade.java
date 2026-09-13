package de.augmentia.quad.core.agent;

/**
 * Manages HITL (Human-in-the-Loop) pause state for the agent.
 * Extracted from Agent.java to separate pause/approve/reject concerns.
 */
public class HitlFacade {

    private volatile boolean paused;
    private volatile String pauseReason;

    /**
     * Marks the agent as paused while a HITL checkpoint is pending.
     * The executing thread is blocked by the checkpoint {@code await} itself,
     * so this method only records observable state and returns immediately.
     */
    public void pauseExecution() {
        this.paused = true;
    }

    public void approve() {
        this.paused = false;
        this.pauseReason = null;
    }

    public void reject(String reason) {
        this.paused = false;
        this.pauseReason = reason;
    }

    public boolean isPaused() {
        return paused;
    }

    public String pauseReason() {
        return pauseReason;
    }
}
