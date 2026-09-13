package de.augmentia.quad.e2e;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.session.AgentSessionState;

public class TestAgent extends Agent {
    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }
}