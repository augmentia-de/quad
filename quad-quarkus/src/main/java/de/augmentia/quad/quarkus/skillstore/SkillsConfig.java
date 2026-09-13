package de.augmentia.quad.quarkus.skillstore;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Configuration for the DB-based skill store (Port 06).
 */
@ApplicationScoped
public class SkillsConfig {

    @ConfigProperty(name = "quad.skills.store.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "quad.skills.store.auto-load", defaultValue = "false")
    boolean autoLoadFromPersonas;

    public SkillsConfig() {}

    public SkillsConfig(boolean enabled, boolean autoLoad) {
        this.enabled = enabled;
        this.autoLoadFromPersonas = autoLoad;
    }

    public boolean isEnabled() { return enabled; }
    public boolean shouldAutoLoadFromPersonas() { return enabled && autoLoadFromPersonas; }
}
