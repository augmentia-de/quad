package de.augmentia.quad.core.hitl.checkpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmailChannel implements CheckpointChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    @FunctionalInterface
    public interface NotificationSender {
        void send(String subject, String body);
    }

    private static NotificationSender senderStub = (sub, body) ->
        log.warn("EmailChannel: Kein externer Sender registriert! Nachricht verloren.");

    public static void registerSender(NotificationSender sender) {
        senderStub = sender;
    }

    @Override
    public void notify(Checkpoint checkpoint) {
        log.info("EmailChannel: Triggering notification for checkpoint {}", checkpoint.id());

        String subject = "HITL Checkpoint erreicht: " + checkpoint.toolName();
        String body = String.format(
            "Ein Human-in-the-Loop Checkpoint wurde erreicht.\n" +
                "Session-ID: %s\nTool-Name: %s\nCheckpoint-ID: %s",
            checkpoint.sessionId(), checkpoint.toolName(), checkpoint.id()
        );

        senderStub.send(subject, body);
    }
}
