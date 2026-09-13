package de.augmentia.quad.quarkus.messaging.email;

import de.augmentia.quad.core.agent.messaging.InboundChannel;
import de.augmentia.quad.core.agent.messaging.QuadMessage;
import de.augmentia.quad.quarkus.messaging.MessagingRouter;
import de.augmentia.quad.quarkus.messaging.TopicAgentMapping;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
@IfBuildProperty(name = "quad.messaging.email.inbound.enabled", stringValue = "true")
public class EmailInboundChannel implements InboundChannel {

    private static final Logger log = Logger.getLogger(EmailInboundChannel.class);

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Inject
    TopicAgentMapping mapping;

    @Inject
    MessagingRouter router;

    @ConfigProperty(name = "quad.messaging.email.inbound.host", defaultValue = "")
    String host;

    @ConfigProperty(name = "quad.messaging.email.inbound.port", defaultValue = "993")
    int port;

    @ConfigProperty(name = "quad.messaging.email.inbound.username", defaultValue = "")
    String username;

    @ConfigProperty(name = "quad.messaging.email.inbound.password", defaultValue = "")
    String password;

    @ConfigProperty(name = "quad.messaging.email.inbound.folder", defaultValue = "INBOX")
    String folder;

    @ConfigProperty(name = "quad.messaging.email.inbound.poll-interval-seconds", defaultValue = "30")
    int pollIntervalSeconds;

    @ConfigProperty(name = "quad.messaging.email.inbound.use-ssl", defaultValue = "true")
    boolean useSsl;

    @Override
    public String name() {
        return "email";
    }

    @Override
    public void start(MessageHandler handler) {
        if (host == null || host.isBlank()) {
            log.warn("Email inbound enabled but no host configured (quad.messaging.email.inbound.host), skipping");
            return;
        }
        this.running.set(true);
        log.infof("Email inbound channel started (host=%s, folder=%s, pollInterval=%ds)",
            host, folder, pollIntervalSeconds);
        // TODO: IMAP polling implementation (requires angus-mail dependency)
    }

    @Override
    public void stop() {
        this.running.set(false);
        log.info("Email inbound channel stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    public void onEmailReceived(String subject, String from, String body) {
        if (!running.get()) {
            log.warn("Email received but handler not ready, discarding");
            return;
        }

        String tenantId = resolveTenant(from);
        String agentId = mapping.agentForTopic("email", tenantId, "inbox");
        if (agentId == null) {
            log.warnf("No agent mapped for email (tenant=%s), discarding message", tenantId);
            return;
        }

        log.infof("Email received: subject=%s from=%s (agentId=%s, tenant=%s)", subject, from, agentId, tenantId);

        QuadMessage message = new QuadMessage(
            UUID.randomUUID().toString(),
            null,
            "email",
            "text",
            body,
            Map.of("subject", subject != null ? subject : "",
                   "from", from != null ? from : "",
                   "agentId", agentId,
                   "tenantId", tenantId),
            java.time.Instant.now()
        );

        router.handleMessage(message);
    }

    private String resolveTenant(String from) {
        if (from == null || from.isBlank()) return "default";
        String domain = from.contains("@") ? from.substring(from.indexOf('@') + 1) : from;
        String envKey = "QUAD_MESSAGING_EMAIL_TENANT_" +
            domain.toUpperCase().replace('.', '_').replace('-', '_');
        return System.getProperty(
            "quad.messaging.email.tenant." + domain,
            System.getenv().getOrDefault(envKey, "default"));
    }
}
