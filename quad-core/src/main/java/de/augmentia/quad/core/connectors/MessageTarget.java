package de.augmentia.quad.core.connectors;

import java.util.Arrays;

/**
 * A parsed chat target token {@code "platform:chat_id[:thread]"} — the opaque reply
 * handle an agent passes back to send a message (mirrors Coworker's
 * {@code format_target} / {@code parse_target}).
 * <p>
 * Examples: {@code "telegram:12345"}, {@code "slack:C0123456"},
 * {@code "slack:T123/C456:1712345678.123456"} (team-qualified Slack channel, threaded).
 */
public record MessageTarget(
    String platform,
    String chatId,
    String threadId
) {

    /** Build the target token: {@code platform:chat_id[:thread]}. */
    public String format() {
        String base = platform + ":" + chatId;
        return (threadId != null && !threadId.isBlank()) ? base + ":" + threadId : base;
    }

    /**
     * Parse a target token. Splits on the first two colons; the thread part may itself
     * contain colons and is joined back whole. Null/blank tokens or missing platform /
     * chat id throw {@link IllegalArgumentException}.
     */
    public static MessageTarget parse(String target) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException(
                "invalid target '" + target + "' (expected 'platform:chat_id[:thread]')");
        }
        String[] parts = target.split(":", -1);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException(
                "invalid target '" + target + "' (expected 'platform:chat_id[:thread]')");
        }
        String thread = null;
        if (parts.length > 2) {
            String joined = String.join(":", Arrays.copyOfRange(parts, 2, parts.length));
            thread = joined.isBlank() ? null : joined;
        }
        return new MessageTarget(parts[0], parts[1], thread);
    }
}