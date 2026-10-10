package de.augmentia.quad.core.workflow.run.event;

import java.time.Instant;

/**
 * One immutable entry of the append-only run log.
 *
 * <p>The {@code sequence} is assigned by the {@link WorkflowRunEventStore} on append; use
 * {@link #builder()} to construct an event and {@link #withSequence(long)} for the store.</p>
 *
 * @param runId     owning run
 * @param sequence  monotonically increasing per store; 0 before append
 * @param type      event type
 * @param nodeId    node the event belongs to; {@code null} for run-level events
 * @param status    optional status snapshot at the time of the event
 * @param timestamp ISO-8601 instant
 * @param output    optional output payload (JSON string)
 * @param error     optional error message
 * @param reason    optional reason (HITL decision, override note, …)
 * @param attempt   attempt number; {@code null} when not applicable
 * @param otelSpanId optional OTel span correlation id
 */
public record WorkflowRunEvent(
        String runId,
        long sequence,
        WorkflowRunEventType type,
        String nodeId,
        String status,
        String timestamp,
        String output,
        String error,
        String reason,
        Integer attempt,
        String otelSpanId
) {

    public WorkflowRunEvent {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        if (timestamp == null || timestamp.isBlank()) {
            timestamp = Instant.now().toString();
        }
    }

    public WorkflowRunEvent withSequence(long newSequence) {
        return new WorkflowRunEvent(runId, newSequence, type, nodeId, status, timestamp,
                output, error, reason, attempt, otelSpanId);
    }

    public boolean isRunLevel() {
        return nodeId == null || nodeId.isBlank();
    }

    public static Builder builder(WorkflowRunEventType type) {
        return new Builder(type);
    }

    /** Fluent builder; {@code runId} and {@code type} are required. */
    public static final class Builder {
        private String runId;
        private final WorkflowRunEventType type;
        private String nodeId;
        private String status;
        private String timestamp;
        private String output;
        private String error;
        private String reason;
        private Integer attempt;
        private String otelSpanId;

        private Builder(WorkflowRunEventType type) {
            this.type = type;
        }

        public Builder runId(String runId) { this.runId = runId; return this; }

        public Builder node(String nodeId) { this.nodeId = nodeId; return this; }

        public Builder status(String status) { this.status = status; return this; }

        public Builder timestamp(String timestamp) { this.timestamp = timestamp; return this; }

        public Builder output(String output) { this.output = output; return this; }

        public Builder error(String error) { this.error = error; return this; }

        public Builder reason(String reason) { this.reason = reason; return this; }

        public Builder attempt(Integer attempt) { this.attempt = attempt; return this; }

        public Builder otelSpanId(String otelSpanId) { this.otelSpanId = otelSpanId; return this; }

        public WorkflowRunEvent build() {
            return new WorkflowRunEvent(runId, 0L, type, nodeId, status, timestamp,
                    output, error, reason, attempt, otelSpanId);
        }
    }
}
