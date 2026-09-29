package de.augmentia.quad.quarkus.skillstore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.List;

/**
 * CDI producers for SkillService configuration.
 */
@ApplicationScoped
public class SkillServiceProducer {

    @Inject
    JdbcSkillStore store;

    @Inject
    JdbcAgentSkillStore agentSkillStore;

    @ConfigProperty(name = "quad.skills.dir", defaultValue = "skills")
    String skillsDir;

    @Produces
    @ApplicationScoped
    public SkillService skillService() {
        return new SkillService(store, List.of(Path.of(skillsDir)), agentSkillStore);
    }
}