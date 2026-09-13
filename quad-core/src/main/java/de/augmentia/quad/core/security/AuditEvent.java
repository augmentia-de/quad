package de.augmentia.quad.core.security;

import java.time.Instant;
import java.util.Set;

/**
 * Immutable audit event record for every security-relevant action.
 * Written by AuditLogger implementations (e.g. JsonlAuditLogger).
 */
public record AuditEvent(
    Instant timestamp,
    String userId,
    Set<String> roles,
    String sessionId,
    String toolName,
    String toolArgs,
    String result,
    boolean isError,
    long durationMs,
    String correlationId,
    String tokenId,
    String eventType
) {

    public static AuditEvent toolCall(Instant timestamp, String userId, Set<String> roles,
                                      String sessionId, String toolName, String toolArgs,
                                      String result, boolean isError, long durationMs,
                                      String correlationId, String tokenId) {
        return new AuditEvent(
            timestamp, userId, roles, sessionId,
            toolName, toolArgs, result, isError, durationMs,
            correlationId, tokenId, "TOOL_CALL"
        );
    }

    public static AuditEvent accessDenied(Instant timestamp, String userId, Set<String> roles,
                                          String sessionId, String toolName, String reason,
                                          String correlationId) {
        return new AuditEvent(
            timestamp, userId, roles, sessionId,
            toolName, null, "DENIED: " + reason, true, 0,
            correlationId, null, "ACCESS_DENIED"
        );
    }

    public static AuditEvent agentStarted(Instant timestamp, String userId, Set<String> roles,
                                          String sessionId, String agentId, String correlationId) {
        return new AuditEvent(
            timestamp, userId, roles, sessionId,
            "agent:" + agentId, null, "Agent started", false, 0,
            correlationId, null, "AGENT_STARTED"
        );
    }

    public static AuditEvent checkpointAction(Instant timestamp, String userId, Set<String> roles,
                                              String sessionId, String checkpointId, String action,
                                              String correlationId) {
        return new AuditEvent(
            timestamp, userId, roles, sessionId,
            "checkpoint:" + checkpointId, null, action, false, 0,
            correlationId, null, "CHECKPOINT_" + action.toUpperCase()
        );
    }
}
