package de.augmentia.quad.quarkus.agent;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.quarkus.agent.config.AgentBuilderConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AgentBuilderFactory {

    @Inject
    AgentBuilderConfig config;

    public <T extends Agent> AgentBuilder<T> forClass(Class<T> agentClass) {
        AgentBuilder<T> builder = AgentBuilder.create(agentClass);

        if (config.isLoggingEnabled()) {
            builder.withLogging();
        }

        if (config.isDefaultsEnabled()) {
            builder.withDefaults();
        }

        builder.withWorkspace(config.getWorkspace());

        return builder;
    }
}