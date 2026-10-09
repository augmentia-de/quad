package de.augmentia.quad.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.config.StructuredOutputConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Canonical, framework-neutral configuration of an agent.
 *
 * <p>This is the single configuration model shared by the programmatic
 * {@link AgentBuilder}, the Quarkus backend and the standalone
 * {@code quad-workflow-worker}. It carries no CDI and no Quarkus types, so it can be
 * built from environment variables, a JSON/YAML document ({@link #fromJson(String)}) or
 * code. A model is either supplied directly ({@link Builder#model}) or resolved from the
 * environment ({@link Builder#modelFromEnv}).</p>
 *
 * <pre>{@code
 * AgentConfig cfg = AgentConfig.builder()
 *     .modelFromEnv()
 *     .systemPrompt("You are ...")
 *     .maxReActIterations(10)
 *     .allowedTools(Set.of("read_file", "write_file"))
 *     .build();
 * }</pre>
 */
public final class AgentConfig {

    private final ChatModel model;
    private final boolean modelFromEnv;
    private final String modelKey;
    private final String systemPrompt;
    private final String userMessageTemplate;
    private final String cachePrefix;
    private final Double temperature;
    private final Integer maxOutputTokens;
    private final Integer maxReActIterations;
    private final Integer contextMaxTokens;
    private final StructuredOutputConfig structuredOutput;
    private final Set<String> allowedTools;
    private final List<Path> skillDirs;
    private final List<String> initialSkills;
    private final boolean skillSearchEnabled;
    private final Path workspace;

    private AgentConfig(Builder b) {
        this.model = b.model;
        this.modelFromEnv = b.modelFromEnv;
        this.modelKey = b.modelKey;
        this.systemPrompt = b.systemPrompt;
        this.userMessageTemplate = b.userMessageTemplate;
        this.cachePrefix = b.cachePrefix;
        this.temperature = b.temperature;
        this.maxOutputTokens = b.maxOutputTokens;
        this.maxReActIterations = b.maxReActIterations;
        this.contextMaxTokens = b.contextMaxTokens;
        this.structuredOutput = b.structuredOutput;
        this.allowedTools = Set.copyOf(b.allowedTools);
        this.skillDirs = List.copyOf(b.skillDirs);
        this.initialSkills = List.copyOf(b.initialSkills);
        this.skillSearchEnabled = b.skillSearchEnabled;
        this.workspace = b.workspace;
    }

    public ChatModel model() { return model; }

    public boolean modelFromEnv() { return modelFromEnv; }

    /** Opaque model identifier for registries; not resolved here. */
    public String modelKey() { return modelKey; }

    public String systemPrompt() { return systemPrompt; }

    public String userMessageTemplate() { return userMessageTemplate; }

    public String cachePrefix() { return cachePrefix; }

    public Double temperature() { return temperature; }

    public Integer maxOutputTokens() { return maxOutputTokens; }

    public Integer maxReActIterations() { return maxReActIterations; }

    public Integer contextMaxTokens() { return contextMaxTokens; }

    public StructuredOutputConfig structuredOutput() { return structuredOutput; }

    public Set<String> allowedTools() { return allowedTools; }

    public List<Path> skillDirs() { return skillDirs; }

    public List<String> initialSkills() { return initialSkills; }

    public boolean skillSearchEnabled() { return skillSearchEnabled; }

    public Path workspace() { return workspace; }

    /**
     * Derives LangChain4j request parameters from the token/sampling settings,
     * or {@code null} when neither temperature nor maxOutputTokens is set.
     */
    public ChatRequestParameters toChatParameters() {
        if (temperature == null && maxOutputTokens == null) {
            return null;
        }
        var builder = DefaultChatRequestParameters.builder();
        if (temperature != null) builder.temperature(temperature);
        if (maxOutputTokens != null) builder.maxOutputTokens(maxOutputTokens);
        return builder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Parses a JSON document into an {@link AgentConfig}. Unknown keys are ignored.
     * Supported keys: {@code modelKey}, {@code modelFromEnv}, {@code systemPrompt},
     * {@code userMessageTemplate}, {@code cachePrefix}, {@code temperature},
     * {@code maxOutputTokens}, {@code maxReActIterations}, {@code contextMaxTokens},
     * {@code allowedTools[]}, {@code skillDirs[]}, {@code initialSkills[]},
     * {@code skillSearchEnabled}, {@code workspace}, {@code structuredOutputSchema}
     * (dynamic) or {@code structuredOutputClass} (fully-qualified class name).
     */
    public static AgentConfig fromJson(String json) {
        if (json == null || json.isBlank()) {
            return builder().build();
        }
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            Builder b = builder();
            if (root.hasNonNull("modelKey")) b.modelKey(root.get("modelKey").asText());
            if (root.hasNonNull("modelFromEnv")) b.modelFromEnv();
            if (root.hasNonNull("systemPrompt")) b.systemPrompt(root.get("systemPrompt").asText());
            if (root.hasNonNull("userMessageTemplate")) b.userMessageTemplate(root.get("userMessageTemplate").asText());
            if (root.hasNonNull("cachePrefix")) b.cachePrefix(root.get("cachePrefix").asText());
            if (root.hasNonNull("temperature")) b.temperature(root.get("temperature").asDouble());
            if (root.hasNonNull("maxOutputTokens")) b.maxOutputTokens(root.get("maxOutputTokens").asInt());
            if (root.hasNonNull("maxReActIterations")) b.maxReActIterations(root.get("maxReActIterations").asInt());
            if (root.hasNonNull("contextMaxTokens")) b.contextMaxTokens(root.get("contextMaxTokens").asInt());
            if (root.hasNonNull("skillSearchEnabled")) b.skillSearch(root.get("skillSearchEnabled").asBoolean());
            if (root.hasNonNull("workspace")) b.workspace(Path.of(root.get("workspace").asText()));

            if (root.has("allowedTools")) {
                for (JsonNode n : root.get("allowedTools")) b.allowedTools(n.asText());
            }
            if (root.has("skillDirs")) {
                for (JsonNode n : root.get("skillDirs")) b.skillDir(Path.of(n.asText()));
            }
            if (root.has("initialSkills")) {
                List<String> names = new ArrayList<>();
                for (JsonNode n : root.get("initialSkills")) names.add(n.asText());
                b.initialSkills(names);
            }
            if (root.hasNonNull("structuredOutputSchema")) {
                b.structuredOutput(StructuredOutputConfig.dynamicSchema(root.get("structuredOutputSchema").asText()));
            } else if (root.hasNonNull("structuredOutputClass")) {
                b.structuredOutput(StructuredOutputConfig.staticModel(
                        Class.forName(root.get("structuredOutputClass").asText())));
            }
            return b.build();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid AgentConfig JSON", e);
        }
    }

    /** Mutable builder for {@link AgentConfig}. */
    public static final class Builder {

        private ChatModel model;
        private boolean modelFromEnv;
        private String modelKey;
        private String systemPrompt;
        private String userMessageTemplate;
        private String cachePrefix;
        private Double temperature;
        private Integer maxOutputTokens;
        private Integer maxReActIterations;
        private Integer contextMaxTokens;
        private StructuredOutputConfig structuredOutput;
        private final Set<String> allowedTools = new LinkedHashSet<>();
        private final List<Path> skillDirs = new ArrayList<>();
        private final List<String> initialSkills = new ArrayList<>();
        private boolean skillSearchEnabled;
        private Path workspace;

        public Builder model(ChatModel model) { this.model = model; return this; }

        public Builder modelFromEnv() { this.modelFromEnv = true; return this; }

        public Builder modelKey(String modelKey) { this.modelKey = modelKey; return this; }

        public Builder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }

        public Builder userMessageTemplate(String template) { this.userMessageTemplate = template; return this; }

        public Builder cachePrefix(String cachePrefix) { this.cachePrefix = cachePrefix; return this; }

        public Builder temperature(Double temperature) { this.temperature = temperature; return this; }

        public Builder maxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; return this; }

        public Builder maxReActIterations(Integer maxReActIterations) { this.maxReActIterations = maxReActIterations; return this; }

        public Builder contextMaxTokens(Integer contextMaxTokens) { this.contextMaxTokens = contextMaxTokens; return this; }

        public Builder structuredOutput(StructuredOutputConfig structuredOutput) { this.structuredOutput = structuredOutput; return this; }

        public Builder allowedTools(Set<String> names) {
            if (names != null) this.allowedTools.addAll(names);
            return this;
        }

        public Builder allowedTools(String... names) {
            if (names != null) this.allowedTools.addAll(List.of(names));
            return this;
        }

        public Builder skillDir(Path dir) {
            if (dir != null) this.skillDirs.add(dir);
            return this;
        }

        public Builder skillDirs(Collection<Path> dirs) {
            if (dirs != null) this.skillDirs.addAll(dirs);
            return this;
        }

        public Builder initialSkills(List<String> names) {
            if (names != null) this.initialSkills.addAll(names);
            return this;
        }

        public Builder skillSearch(boolean enabled) { this.skillSearchEnabled = enabled; return this; }

        public Builder workspace(Path workspace) { this.workspace = workspace; return this; }

        public AgentConfig build() { return new AgentConfig(this); }
    }
}
