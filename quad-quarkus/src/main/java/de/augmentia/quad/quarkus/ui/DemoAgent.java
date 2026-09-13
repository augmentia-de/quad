package de.augmentia.quad.quarkus.ui;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.session.AgentSessionState;

public class DemoAgent extends Agent {
    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }
}