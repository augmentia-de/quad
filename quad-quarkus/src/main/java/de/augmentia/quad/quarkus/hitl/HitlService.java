package de.augmentia.quad.quarkus.hitl;

import de.augmentia.quad.core.hitl.checkpoint.CheckpointService;
import de.augmentia.quad.core.hitl.checkpoint.EmailChannel;
import de.augmentia.quad.core.hitl.checkpoint.KafkaCheckpointChannel;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

@ApplicationScoped
public class HitlService {

    private static final Logger log = LoggerFactory.getLogger(HitlService.class);

    @Inject
    ReactiveMailer mailer;

    @Inject
    SSEChannel sseChannel;

    @ConfigProperty(name = "quad.hitl.approval-tools")
    Optional<String> approvalTools;

    @ConfigProperty(name = "quad.hitl.timeout-seconds", defaultValue = "120")
    long timeoutSeconds;

    @ConfigProperty(name = "quad.hitl.notification.emails")
    Optional<String> notificationEmails;

    /**
     * Comma-separated lis of enabled notification channels: sse, email, kafka.
     * When the UI (SSE) is connected the checkpoint is only pushed to the UI;
     * otherwise the async channels (email, kafka) are used as fallback.
     */
    @ConfigProperty(name = "quad.hitl.notification.channels", defaultValue = "sse,email")
    Optional<String> notificationChannels;

    @ConfigProperty(name = "quad.hitl.enabled", defaultValue = "true")
    boolean hitlEnabled;

    private volatile CheckpointService checkpointService;

    @PostConstruct
    void init() {
        CheckpointService service = new CheckpointService(approvalTools.orElse(""), timeoutSeconds * 1000);
        service.setHitlEnabled(hitlEnabled);
        service.setUiConnectionProvider(sseChannel);

        wireEmailSender();

        String channels = notificationChannels.orElse("sse,email").trim();
        for (String ch : channels.split(",")) {
            String name = ch.trim().toLowerCase();
            if (name.isEmpty()) continue;
            switch (name) {
                case "sse" -> service.registerUiChannel(sseChannel);
                case "email" -> service.registerAsyncChannel(new EmailChannel());
                case "kafka" -> service.registerAsyncChannel(new KafkaCheckpointChannel());
                default -> log.warn("Unknown HITL notification channel: {} (available: sse, email, kafka)", name);
            }
        }

        this.checkpointService = service;
        log.info("HITL initialisiert. Approval-Tools: '{}', Timeout: {}s, Channels: {}, UI: {}",
            approvalTools.orElse(""), timeoutSeconds, channels,
            sseChannel.anyUiConnected() ? "connected" : "not connected");
    }

    private void wireEmailSender() {
        if (notificationEmails != null && notificationEmails.isPresent()
                && !notificationEmails.get().isBlank()) {
            EmailChannel.registerSender((subject, body) -> {
                var mail = Mail.withText(notificationEmails.get(), subject, body);
                mailer.send(mail).subscribe().with(
                    m -> log.debug("HITL-Notification gesendet: {}", subject),
                    t -> log.warn("HITL-Notification fehlgeschlagen: {}", t.getMessage()));
            });
        } else {
            EmailChannel.registerSender((subject, body) ->
                log.info("HITL-Notification (kan Emails konfiguriert): {}", subject));
        }
    }

    public CheckpointService checkpointService() {
        return checkpointService;
    }
}