package de.augmentia.quad.core.hitl.checkpoint;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public class Checkpoint {

    public enum Status { PENDING, APPROVED, REJECTED }

    private final String id;
    private final String sessionId;
    private final String toolName;
    private final String arguments;
    private final Instant createdAt;
    private volatile Status status;
    private volatile String feedback;
    private final CompletableFuture<Checkpoint> future;

    public Checkpoint(String id, String sessionId, String toolName, String arguments) {
        this.id = id;
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.arguments = arguments;
        this.createdAt = Instant.now();
        this.status = Status.PENDING;
        this.feedback = null;
        this.future = new CompletableFuture<>();
    }

    public String id() { return id; }
    public String sessionId() { return sessionId; }
    public String toolName() { return toolName; }
    public String arguments() { return arguments; }
    public Instant createdAt() { return createdAt; }
    public Status status() { return status; }
    public String feedback() { return feedback; }
    public CompletableFuture<Checkpoint> future() { return future; }

    public void approve(String feedback) {
        this.status = Status.APPROVED;
        this.feedback = feedback;
        future.complete(this);
    }

    public void reject(String feedback) {
        this.status = Status.REJECTED;
        this.feedback = feedback;
        future.complete(this);
    }
}
