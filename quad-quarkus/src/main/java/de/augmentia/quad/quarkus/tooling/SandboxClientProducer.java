package de.augmentia.quad.quarkus.tooling;

import de.augmentia.quad.core.agent.runtime.SandboxClient;
import de.augmentia.quad.core.tool.sandbox.SandboxPoolClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class SandboxClientProducer {

    @Inject
    SandboxPoolClient pool;

    @Produces
    @ApplicationScoped
    public SandboxClient createSandboxClient() {
        return new SandboxClient() {
            @Override
            public String run(String script) {
                return pool.run(script);
            }

            @Override
            public String run(String script, String sessionId) {
                return pool.run(script, sessionId);
            }
        };
    }
}
