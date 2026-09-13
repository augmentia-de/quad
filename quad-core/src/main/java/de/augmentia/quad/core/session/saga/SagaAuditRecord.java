package de.augmentia.quad.core.session.saga;

import java.io.Serializable;

public record SagaAuditRecord(String sessionId, String toolName, String executionId)
        implements Serializable {}