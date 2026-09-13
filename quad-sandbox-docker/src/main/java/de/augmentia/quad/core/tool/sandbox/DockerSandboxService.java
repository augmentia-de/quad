package de.augmentia.quad.core.tool.sandbox;

import de.augmentia.quad.core.agent.runtime.SandboxClient;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Alternative {@link SandboxClient} backed by the Docker sandbox. Only active
 * when explicitly selected (e.g. via a {@code beans.xml} alternative list).
 * The default wiring routes through {@link SandboxPoolClient}.
 */
@Alternative
@ApplicationScoped
public class DockerSandboxService implements SandboxClient {

    @Inject
    SandboxPoolClient pool;

    @Override
    public String run(String script) {
        return pool.run(script, null);
    }

    @Override
    public String run(String script, String sessionId) {
        return pool.run(script, sessionId);
    }
}
