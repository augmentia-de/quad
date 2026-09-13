package de.augmentia.quad.core.connectors;

import java.util.Set;

/**
 * Optional SPI for request/response outbound messaging on a {@link Connector}: the
 * connector-side counterpart to the stateless {@link OutboundSender}. A connector that
 * implements this can be handed a chat {@link MessageTarget} and send a message itself,
 * resolving the platform credential (e.g. its own vault secret) at call time — the
 * mirror of the Python reference's {@code adapter.send(...)}.
 * <p>
 * Implementations are free to forward to their platform's {@link OutboundSender} in
 * {@code connectors/sender}; the interface exists so callers (Gateway,
 * {@code /api/connectors/{id}/send}) can address a connector without knowing its
 * platform-specific plumbing. Pure-inbound connectors (e.g. GitHub) do not implement it.
 */
public interface OutboundMessaging {

    /**
     * Send one message to a parsed chat target. The backend credential is resolved by the
     * connector (vault), never passed by the caller.
     *
     * @param target parsed {@code platform:chat_id[:thread]} target
     * @param text   message body
     * @return the send outcome (never {@code null})
     */
    SendResult send(MessageTarget target, String text);

    /**
     * Convenience for callers holding the raw target token.
     *
     * @param target raw {@code platform:chat_id[:thread]} token
     * @param text   message body
     * @return the send outcome (never {@code null})
     */
    default SendResult send(String target, String text) {
        return send(MessageTarget.parse(target), text);
    }

    /**
     * Optional enumeration of concrete chat targets this connector can send to (e.g. the
     * configured/monitored channel ids). Used for discovery; unknown targets may still be
     * addressable.
     */
    default Set<String> outboundTargets() {
        return Set.of();
    }
}