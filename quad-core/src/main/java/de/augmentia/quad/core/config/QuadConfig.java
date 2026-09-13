package de.augmentia.quad.core.config;

import java.time.Duration;
import java.util.Set;

public class QuadConfig {
    private boolean dynamicDiscovery = false;
    private int maxIterations = 10;
    private int capabilityCacheSize = 1000;
    private int capabilityCacheExpireMinutes = 30;
    private int sandboxPoolSize = 5;
    private String defaultModel = "gpt-4o";
    private Duration sessionTtl = Duration.ofMinutes(30);
    private Set<String> builtInTools = Set.of("executeBash", "readFile", "writeFile");

    public boolean isDynamicDiscovery() { return dynamicDiscovery; }
    public void setDynamicDiscovery(boolean dynamicDiscovery) { this.dynamicDiscovery = dynamicDiscovery; }
    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
    public int getCapabilityCacheSize() { return capabilityCacheSize; }
    public void setCapabilityCacheSize(int capabilityCacheSize) { this.capabilityCacheSize = capabilityCacheSize; }
    public int getCapabilityCacheExpireMinutes() { return capabilityCacheExpireMinutes; }
    public void setCapabilityCacheExpireMinutes(int capabilityCacheExpireMinutes) { this.capabilityCacheExpireMinutes = capabilityCacheExpireMinutes; }
    public int getSandboxPoolSize() { return sandboxPoolSize; }
    public void setSandboxPoolSize(int sandboxPoolSize) { this.sandboxPoolSize = sandboxPoolSize; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public Duration getSessionTtl() { return sessionTtl; }
    public void setSessionTtl(Duration sessionTtl) { this.sessionTtl = sessionTtl; }
    public Set<String> getBuiltInTools() { return builtInTools; }
    public void setBuiltInTools(Set<String> builtInTools) { this.builtInTools = builtInTools; }
}