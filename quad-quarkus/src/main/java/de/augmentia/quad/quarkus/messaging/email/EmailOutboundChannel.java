package de.augmentia.quad.quarkus.messaging.email;

import de.augmentia.quad.core.agent.messaging.QuadMessage;
import de.augmentia.quad.core.agent.messaging.OutboundChannel;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
@IfBuildProperty(name = "quad.messaging.email.outbound.enabled", stringValue = "true")
public class EmailOutboundChannel implements OutboundChannel {

    private static final Logger log = Logger.getLogger(EmailOutboundChannel.class);

    private final ReactiveMailer mailer;
    private String fromAddress = "agent@quad.local";

    public EmailOutboundChannel(ReactiveMailer mailer) {
        this.mailer = mailer;
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public void send(QuadMessage message) {
        String to = message.headers().get("to");
        if (to == null || to.isBlank()) {
            log.warn("No recipient specified in email message headers, discarding");
            return;
        }

        String subject = "QUAD Agent Response [" + message.sessionId() + "]";
        Mail mail = Mail.withText(to, subject, message.payload())
            .setFrom(fromAddress);

        mailer.send(mail)
            .subscribe()
            .with(
                m -> log.debugf("Email sent to %s", to),
                t -> log.errorf("Failed to send email to %s: %s", to, t.getMessage())
            );
    }

    @Override
    public boolean isAvailable() {
        return mailer != null;
    }
}
