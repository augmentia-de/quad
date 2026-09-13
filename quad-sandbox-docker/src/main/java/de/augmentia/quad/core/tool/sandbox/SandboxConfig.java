package de.augmentia.quad.core.tool.sandbox;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Configuration for the bash sandbox. All values may be overridden via
 * {@code quad.sandbox.*} system properties or environment-derived config.
 */
@ApplicationScoped
public class SandboxConfig {

    private final boolean enabled;
    private final String image;
    private final String memory;
    private final long timeoutMs;
    private final boolean noNetwork;

    @Inject
    public SandboxConfig(
            @ConfigProperty(name = "quad.sandbox.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "quad.sandbox.image", defaultValue = "quad-runner:latest") String image,
            @ConfigProperty(name = "quad.sandbox.memory", defaultValue = "512m") String memory,
            @ConfigProperty(name = "quad.sandbox.timeout-ms", defaultValue = "120000") long timeoutMs,
            @ConfigProperty(name = "quad.sandbox.network", defaultValue = "false") boolean noNetwork) {
        this.enabled = enabled;
        this.image = image;
        this.memory = memory;
        this.timeoutMs = timeoutMs;
        this.noNetwork = noNetwork;
    }

    /** For tests / manual construction. */
    public static SandboxConfig of(boolean enabled, String image, String memory, long timeoutMs, boolean noNetwork) {
        return new SandboxConfig(enabled, image, memory, timeoutMs, noNetwork, true);
    }

    private SandboxConfig(boolean enabled, String image, String memory, long timeoutMs, boolean noNetwork, boolean ignored) {
        this.enabled = enabled;
        this.image = image;
        this.memory = memory;
        this.timeoutMs = timeoutMs;
        this.noNetwork = noNetwork;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String image() {
        return image;
    }

    public String memory() {
        return memory;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    /** True if the container should run without network access. */
    public boolean noNetwork() {
        return noNetwork;
    }
}
