package de.augmentia.quad.core.session.saga;

import de.augmentia.quad.core.session.AgentSessionState;

public interface CompensatingAction {
    void compensate(AgentSessionState state) throws Exception;

    record SagaStep(String toolName, String executionId, CompensatingAction compensation) {}
}