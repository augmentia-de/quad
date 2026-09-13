package de.augmentia.quad.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.agent.runtime.AgentEventPublisher;
import de.augmentia.quad.core.config.ModelFactory;
import de.augmentia.quad.core.config.StructuredOutputConfig;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import de.augmentia.quad.core.guardrails.Guardrail;
import de.augmentia.quad.core.guardrails.GuardrailPlugin;
import de.augmentia.quad.core.guardrails.GuardrailResult;
import de.augmentia.quad.core.hook.pipeline.HookRegistry;
import de.augmentia.quad.core.observability.LoggingHook;
import de.augmentia.quad.core.agent.runtime.IdempotencyStore;
import de.augmentia.quad.core.agent.runtime.OTelAgentTracer;
import de.augmentia.quad.core.agent.runtime.ToolExecutor;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.SubAgentTool;
import de.augmentia.quad.core.tool.ToolArgsMapper;

import de.augmentia.quad.core.capability.CapabilityRegistry;
import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.hook.plugin.AgentSkillsPlugin;
import de.augmentia.quad.core.hook.plugin.SkillActivationHook;
import de.augmentia.quad.core.tool.SkillSearchTool;
import de.augmentia.quad.core.tool.ToolActivatorTool;
import dev.langchain4j.model.chat.ChatModel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builder pattern for creating agents with sensible defaults.
 */
public class AgentBuilder<T extends Agent> {

    private final Class<T> agentClass;
    private T agent;
    private QuadToolRegistry toolRegistry;
    private AgentEventPublisher eventPublisher;
    private HookRegistry hookRegistry;
    
    private boolean withLogging = false;
    private boolean withDefaults = false;
    private Path workspace = Path.of(".");
    private Set<String> toolNames = null;
    private final List<Map.Entry<String, Agent>> subAgents = new ArrayList<>();
    private StructuredOutputConfig structuredOutputConfig;
    
    private AgentBuilder(Class<T> agentClass) {
        this.agentClass = agentClass;
    }

    // Skills
    private final List<Path> skillDirs = new ArrayList<>();
    private List<String> initialSkills = List.of();
    private boolean skillSearchEnabled = false;
    private ChatModel subAgentModel;
    private ChatRequestParameters chatParameters;
    
    /** Creates a new builder for the given agent class. */
    public static <T extends Agent> AgentBuilder<T> create(Class<T> agentClass) {
        return new AgentBuilder<>(agentClass);
    }
    
    /** Configures the LLM from environment variables (OPENAI_API_KEY, OPENAI_MODEL). */
    public AgentBuilder<T> withLlmFromEnv() {
        try {
            agent = agentClass.getDeclaredConstructor().newInstance();
            agent.setLlm(ModelFactory.createOpenAiFromEnv());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create agent instance", e);
        }
        return this;
    }
    
    /** Adds logging hook to the event publisher. */
    public AgentBuilder<T> withLogging() {
        withLogging = true;
        return this;
    }
    
    /** Sets the workspace path. */
    public AgentBuilder<T> withWorkspace(Path workspace) {
        this.workspace = workspace;
        return this;
    }
    
    /** Configures tools from a specific set of names. */
    public AgentBuilder<T> withTools(Set<String> toolNames) {
        this.toolNames = toolNames;
        return this;
    }

    /** Registers a sub-agent as a tool that delegates execution to another agent. */
    public AgentBuilder<T> withSubAgent(String toolName, Agent subAgent) {
        subAgents.add(Map.entry(toolName, subAgent));
        return this;
    }
    
    /** Applies standard defaults (logging, guards). */
    public AgentBuilder<T> withDefaults() {
        withDefaults = true;
        withLogging = true;
        return this;
    }
    
    /** Creates a development profile agent with mocks and in-memory tools. */
    public AgentBuilder<T> withDevProfile() {
        withDefaults = true;
        withLogging = true;
        return this;
    }
    
    /** Creates a production profile agent with full features enabled. */
    public AgentBuilder<T> withProdProfile() {
        withDefaults = true;
        withLogging = true;
        return this;
    }

    /** Adds a skill directory for skill discovery. */
    public AgentBuilder<T> withSkills(Path skillDir) {
        skillDirs.add(skillDir);
        return this;
    }

    /** Sets skills to activate on agent start (max 3). */
    public AgentBuilder<T> withInitialSkills(List<String> names) {
        initialSkills = names != null ? List.copyOf(names) : List.of();
        return this;
    }

    /** Enables skill_search tool for runtime skill activation. */
    public AgentBuilder<T> withSkillSearch(boolean enabled) {
        skillSearchEnabled = enabled;
        return this;
    }

    /** Sets the LLM model for capability search sub-agent. */
    public AgentBuilder<T> withSubAgentModel(ChatModel model) {
        this.subAgentModel = model;
        return this;
    }

    /** Sets per-agent chat request parameters (temperature, maxTokens, etc.). */
    public AgentBuilder<T> withChatParameters(ChatRequestParameters params) {
        this.chatParameters = params;
        return this;
    }

    /** Sets per-agent chat request parameters from JSON string. */
    public AgentBuilder<T> withChatParametersJson(String json) {
        if (json != null && !json.isBlank()) {
            try {
                var tree = new ObjectMapper().readTree(json);
                var builder = dev.langchain4j.model.chat.request.DefaultChatRequestParameters.builder();
                if (tree.has("temperature") && !tree.get("temperature").isNull())
                    builder.temperature(tree.get("temperature").asDouble());
                if (tree.has("maxOutputTokens") && !tree.get("maxOutputTokens").isNull())
                    builder.maxOutputTokens(tree.get("maxOutputTokens").asInt());
                if (tree.has("topP") && !tree.get("topP").isNull())
                    builder.topP(tree.get("topP").asDouble());
                if (tree.has("topK") && !tree.get("topK").isNull())
                    builder.topK(tree.get("topK").asInt());
                if (tree.has("modelName") && !tree.get("modelName").isNull())
                    builder.modelName(tree.get("modelName").asText());
                this.chatParameters = builder.build();
            } catch (Exception e) {
                throw new RuntimeException("Invalid chat parameters JSON: " + json, e);
            }
        }
        return this;
    }

    /** Configures structured output with a statically-typed output class. */
    public AgentBuilder<T> withStructuredOutput(Class<?> outputClass) {
        this.structuredOutputConfig = StructuredOutputConfig.staticModel(outputClass);
        return this;
    }

    /** Configures structured output with a dynamic JSON schema string. */
    public AgentBuilder<T> withStructuredOutputSchema(String jsonSchema) {
        this.structuredOutputConfig = StructuredOutputConfig.dynamicSchema(jsonSchema);
        return this;
    }

    /** Configures structured output with a full custom config. */
    public AgentBuilder<T> withStructuredOutputConfig(StructuredOutputConfig config) {
        this.structuredOutputConfig = config;
        return this;
    }
    
    /** Builds and returns the configured agent. */
    public T build() {
        if (agent == null) {
            try {
                agent = agentClass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to create agent instance", e);
            }
        }
        
        if (toolRegistry == null) {
            toolRegistry = new QuadToolRegistry(new ToolArgsMapper(new ObjectMapper()));
        }
        
        // Register tools from agent
        toolRegistry.registerFromAgent(agent);
        
        // Filter to specific tool names if configured
        if (toolNames != null && !toolNames.isEmpty()) {
            // Keep only the configured tools
            var allTools = toolRegistry.getAll();
            var filteredTools = allTools.stream()
                .filter(tm -> toolNames.contains(tm.spec().name()))
                .toList();
            // Clear and re-register only filtered tools
            toolRegistry = toolRegistry.withOnly(toolNames);
        }
        
        // Register sub-agent tools (respecting the tool filter, if any)
        for (Map.Entry<String, Agent> entry : subAgents) {
            if (toolNames != null && !toolNames.isEmpty() && !toolNames.contains(entry.getKey())) {
                continue;
            }
            toolRegistry.register(entry.getKey(), new SubAgentTool(entry.getValue(), entry.getKey()));
        }
        
        if (eventPublisher == null) {
            eventPublisher = new AgentEventPublisher();
        }
        
        if (hookRegistry == null) {
            hookRegistry = new HookRegistry();
        }
        
        // Wire everything together
        agent.setToolRegistry(toolRegistry);
        agent.setEventPublisher(eventPublisher);
        agent.setHookRegistry(hookRegistry);

        // ToolExecutor (idempotency + saga + hooks + events) if not already set
        if (agent.getToolExecutor() == null) {
            agent.setToolExecutor(new ToolExecutor(
                new IdempotencyStore(), new OTelAgentTracer(), eventPublisher, hookRegistry));
        }

        if (structuredOutputConfig != null) {
            agent.setStructuredOutputConfig(structuredOutputConfig);
        }

        if (chatParameters != null) {
            agent.setChatParameters(chatParameters);
        }
        
        // Apply logging hook if requested
        if (withLogging) {
            eventPublisher.addEventListener(new LoggingHook()::onEvent);
        }

        // Register skill plugins if skill directories configured
        if (!skillDirs.isEmpty()) {
            registerSkillPlugins();
        }

        // Apply defaults if requested
        if (withDefaults) {
            applyDefaults();
        }
        
        return agent;
    }
    
    private void applyDefaults() {
        Guardrail noPii = (messages, context) ->
            messages.toString().matches(".*[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}.*")
                ? GuardrailResult.block("PII detected in prompt")
                : GuardrailResult.ok();

        agent.addHook(new GuardrailPlugin(List.of(noPii), List.of()));
    }

    private void registerSkillPlugins() {
        var capabilityRegistry = CapabilityRegistry.builder()
            .skillDirs(skillDirs.toArray(new Path[0]))
            .build();
        var allSkills = capabilityRegistry.discoverAllSkills();

        // AgentSkillsPlugin — XML-Injection
        var skillsPlugin = new AgentSkillsPlugin(allSkills, initialSkills, skillSearchEnabled);
        agent.addHook(skillsPlugin);

        // SkillSearchTool
        if (skillSearchEnabled) {
            var skillMap = new HashMap<String, Skill>();
            for (var s : allSkills) skillMap.put(s.name(), s);
            var skillSearchTool = new SkillSearchTool(skillMap, skillsPlugin::activateSkill);
            toolRegistry.register(skillSearchTool.spec().name(), skillSearchTool);
        }

        // ToolActivatorTool
        var toolActivator = new ToolActivatorTool(toolRegistry);
        toolRegistry.register(toolActivator.spec().name(), toolActivator);

        // SkillActivationHook
        var toolToSkill = new HashMap<String, Skill>();
        for (var s : allSkills) {
            if (s.declaredTools() != null) {
                for (var tool : s.declaredTools()) {
                    toolToSkill.put(tool, s);
                }
            }
        }
        var activationHook = new SkillActivationHook(toolToSkill, skillsPlugin);
        agent.addHook(activationHook);
    }
}