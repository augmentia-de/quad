package de.augmentia.quad.core.connectors;

/**
 * Stateless outbound message sender for one chat platform — an {@code (token, chat_id,
 * text, thread_id) -> SendResult} function, mirroring Coworker's {@code Sender} alias.
 * Senders are pure HTTP one-shots (no SDK, no live connection) and are resolved through a
 * swappable registry so tests inject fakes with no network. The token is supplied by the
 * caller (resolved from the vault at call time), never stored on the sender.
 */
@FunctionalInterface
public interface OutboundSender {

    /**
     * Send one message.
     *
     * @param token     platform bot token (e.g. Slack {@code Bearer}, Telegram bot token)
     * @param chatId    destination channel/chat id (team-qualified ids like {@code T…/C…}
     *                  are stripped by the sender)
     * @param text      message body
     * @param threadId  optional reply/thread ts, or {@code null}
     * @return the send outcome (never {@code null})
     */
    SendResult send(String token, String chatId, String text, String threadId);
}