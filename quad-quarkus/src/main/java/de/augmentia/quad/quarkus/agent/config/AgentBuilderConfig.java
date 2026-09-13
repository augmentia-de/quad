package de.augmentia.quad.quarkus.agent.config;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Builder configuration, bound to {@code quad.agent.*} properties
 * (no longer hardcoded).
 */
@RegisterForReflection
@ApplicationScoped
public class AgentBuilderConfig {

    @ConfigProperty(name = "quad.agent.logging", defaultValue = "false")
    boolean logging;

    @ConfigProperty(name = "quad.agent.defaults", defaultValue = "true")
    boolean defaults;

    @ConfigProperty(name = "quad.agent.workspace", defaultValue = ".")
    String workspace;

    @ConfigProperty(name = "quad.agent.tools")
    Optional<String> tools;

    public boolean isLoggingEnabled() { return logging; }

    public boolean isDefaultsEnabled() { return defaults; }

    public Path getWorkspace() { return Path.of(workspace).toAbsolutePath().normalize(); }

    public Set<String> getEnabledTools() {
        if (tools.isEmpty() || tools.get().isBlank()) return Set.of();
        return new LinkedHashSet<>(Arrays.stream(tools.get().split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList());
    }
}