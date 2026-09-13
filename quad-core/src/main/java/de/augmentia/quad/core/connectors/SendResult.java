package de.augmentia.quad.core.connectors;

/**
 * Outcome of a single outbound message send, analogous to Coworker's {@code SendResult}
 * dataclass. Message transport is stateless — a successful {@link #send} returns the
 * platform's message id (e.g. Slack {@code ts}, Telegram {@code message_id}).
 */
public record SendResult(
    boolean ok,
    String messageId,
    String error
) {
    public static SendResult success(String messageId) {
        return new SendResult(true, messageId, null);
    }

    public static SendResult failure(String error) {
        return new SendResult(false, null, error);
    }
}