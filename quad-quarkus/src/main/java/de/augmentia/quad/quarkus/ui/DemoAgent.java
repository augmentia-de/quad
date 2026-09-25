package de.augmentia.quad.quarkus.ui;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.session.AgentSessionState;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class DemoAgent extends Agent {
    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }
}