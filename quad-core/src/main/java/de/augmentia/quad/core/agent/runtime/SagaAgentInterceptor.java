package de.augmentia.quad.core.agent.runtime;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.saga.CompensatingAction.SagaStep;

public class SagaAgentInterceptor {

    public void onBeforeToolExecution(AgentSessionState state, String toolName) {
    }

    public void onAfterToolExecution(AgentSessionState state, String toolName, boolean success) {
        if (!success && state.isSagaFailed()) {
            unwindSaga(state);
        }
    }

    public void unwindSaga(AgentSessionState state) {
        var sagaLog = state.getSagaLog();
        while (!sagaLog.isEmpty()) {
            SagaStep step = sagaLog.pop();
            try {
                step.compensation().compensate(state);
            } catch (Exception e) {
            }
        }
    }
}