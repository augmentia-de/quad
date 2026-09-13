package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.session.AgentSessionState;

@FunctionalInterface
public interface SessionStateFactory {
    AgentSessionState create(Object agent);
}