package de.augmentia.quad.quarkus.ui;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

@RegisterForReflection
@ApplicationScoped
public class UiConfig {

    private final boolean dummyMode;
    private final String model;
    private final String baseUrl;
    private final double costLimit;
    private final Set<String> enabledTools;

    public UiConfig() {
        UiConfig built = builder().fromEnvironment().build();
        this.dummyMode = built.dummyMode;
        this.model = built.model;
        this.baseUrl = built.baseUrl;
        this.costLimit = built.costLimit;
        this.enabledTools = built.enabledTools;
    }

    private UiConfig(Builder builder) {
        this.dummyMode = builder.dummyMode;
        this.model = builder.model;
        this.baseUrl = builder.baseUrl;
        this.costLimit = builder.costLimit;
        this.enabledTools = builder.enabledTools;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public boolean isDummyMode() { return dummyMode; }
    public String getModel() { return model; }
    public String getBaseUrl() { return baseUrl; }
    public double getCostLimit() { return costLimit; }
    public Set<String> getEnabledTools() { return enabledTools; }
    
    public static class Builder {
        private boolean dummyMode = true;
        private String model = "gpt-4o-mini";
        private String baseUrl = "https://api.openai.com/v1";
        private double costLimit = 1.0;
        private Set<String> enabledTools = Set.of("readFile", "writeFile", "webSearch", "webfetch", "findFiles", "grepSearch", "appendFile", "read_file", "write_file", "edit_file", "create_directory", "list_directory", "directory_tree", "move_file", "search_files", "get_file_info", "list_allowed_directories", "create_entities", "create_relations", "add_observations", "delete_entities", "delete_observations", "delete_relations", "read_graph", "search_nodes", "open_nodes");
        
        public Builder fromEnvironment() {
            String dummyModeEnv = System.getenv().getOrDefault("QUAD_DUMMY_MODE", "false");
            String dummyModeProp = System.getProperty("quad.ui.dummy", dummyModeEnv);
            this.dummyMode = Boolean.parseBoolean(dummyModeProp);
            
            this.model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
            this.model = System.getProperty("quad.ui.model", this.model);
            
            this.baseUrl = System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1");
            this.baseUrl = System.getProperty("quad.ui.baseUrl", this.baseUrl);
            
            this.costLimit = Double.parseDouble(System.getenv().getOrDefault("QUAD_COST_LIMIT", "1.0"));
            this.costLimit = Double.parseDouble(System.getProperty("quad.ui.costLimit", String.valueOf(costLimit)));
            
            String tools = System.getenv().getOrDefault("QUAD_TOOLS", "readFile,writeFile,webSearch");
            tools = System.getProperty("quad.ui.tools", tools);
            this.enabledTools = Set.of(tools.split(","));
            
            return this;
        }
        
        public Builder dummyMode(boolean dummyMode) {
            this.dummyMode = dummyMode;
            return this;
        }
        
        public Builder model(String model) {
            this.model = model;
            return this;
        }
        
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }
        
        public Builder costLimit(double costLimit) {
            this.costLimit = costLimit;
            return this;
        }
        
        public Builder enabledTools(Set<String> enabledTools) {
            this.enabledTools = enabledTools;
            return this;
        }
        
        public UiConfig build() {
            return new UiConfig(this);
        }
    }
}