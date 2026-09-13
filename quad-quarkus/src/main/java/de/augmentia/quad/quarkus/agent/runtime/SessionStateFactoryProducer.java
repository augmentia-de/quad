package de.augmentia.quad.quarkus.agent.runtime;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.SessionStateFactory;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.quarkus.agent.config.AgentBuilderConfig;
import de.augmentia.quad.quarkus.agent.config.GrantedDirsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class SessionStateFactoryProducer {

    @Inject
    GrantedDirsConfig grantedDirsConfig;

    @Inject
    AgentBuilderConfig agentBuilderConfig;

    @Produces
    @ApplicationScoped
    public SessionStateFactory defaultSessionStateFactory() {
        return agent -> {
            AgentSessionState state;
            if (agent instanceof Agent a) {
                state = a.createSessionState();
            } else {
                state = AgentSessionState.create("session-" + UUID.randomUUID());
            }
            applyStaticGrants(state);
            return state;
        };
    }

    /** Applies statically configured (QUAD_GRANTED_DIRS) grants to a fresh session state. */
    void applyStaticGrants(AgentSessionState state) {
        if (grantedDirsConfig != null) {
            grantedDirsConfig.directories().forEach((path, writable) -> state.grantDir(path, writable));
        }
        // Make the base workspace (default: process CWD + all subdirs) writable.
        if (agentBuilderConfig != null) {
            state.grantDir(agentBuilderConfig.getWorkspace(), true);
        }
    }
}
