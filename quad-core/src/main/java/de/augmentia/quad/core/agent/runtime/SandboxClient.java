package de.augmentia.quad.core.agent.runtime;

public interface SandboxClient {
    String run(String script);
    String run(String script, String sessionId);
}