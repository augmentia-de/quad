package de.augmentia.quad.core.gdpr;

import de.augmentia.quad.core.hook.pipeline.AgentHook;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public class AuditTrailHook implements AgentHook {

    public interface AuditStore {
        void write(AuditEntry entry);
        List<AuditEntry> findByUserId(String userId);
        List<AuditEntry> findBySessionId(String sessionId);
        List<AuditEntry> findAll();
        boolean verifyChain();
    }

    public record AuditEntry(
        String id,
        Instant timestamp,
        String sessionId,
        String userId,
        String action,
        String toolName,
        boolean isError,
        String hashPrevious,
        String hashPayload
    ) {}

    private final AuditStore store;
    private String lastHash = "";

    public AuditTrailHook(AuditStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "gdpr-audit-trail";
    }

    private String sha256(String input) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest(input.getBytes()));
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    @Override
    public HookResult afterToolCall(HookContexts.AfterToolCallContext ctx, String toolResult) {
        var payload = ctx.toolName() + ":" + (ctx.isError() ? "ERROR" : "OK");
        var hash = sha256(payload);
        var entry = new AuditEntry(
            UUID.randomUUID().toString(),
            Instant.now(),
            ctx.sessionId(),
            extractUserId(ctx.sessionId()),
            "tool_call",
            ctx.toolName(),
            ctx.isError(),
            lastHash,
            hash
        );
        lastHash = hash;
        store.write(entry);
        return new HookResult.Continue();
    }

    @Override
    public HookResult afterAgent(HookContexts.AfterAgentContext ctx, String response) {
        var payload = "agent_response:" + (response != null ? response.length() : 0);
        var hash = sha256(payload);
        var entry = new AuditEntry(
            UUID.randomUUID().toString(),
            Instant.now(),
            ctx.sessionId(),
            extractUserId(ctx.sessionId()),
            "agent_response",
            null,
            false,
            lastHash,
            hash
        );
        lastHash = hash;
        store.write(entry);
        return new HookResult.Continue();
    }

    static String extractUserId(String sessionId) {
        if (sessionId != null && sessionId.contains("::")) {
            return sessionId.substring(0, sessionId.indexOf("::"));
        }
        return sessionId != null ? sessionId : "unknown";
    }
}
