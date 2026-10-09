package de.augmentia.quad.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.agent.runtime.AgentEventPublisher;
import de.augmentia.quad.core.config.ModelFactory;
import de.augmentia.quad.core.config.StructuredOutputConfig;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import de.augmentia.quad.core.guardrails.Guardrail;
import de.augmentia.quad.core.guardrails.GuardrailPlugin;
import de.augmentia.quad.core.guardrails.GuardrailResult;
import de.augmentia.quad.core.hook.pipeline.AgentHook;
import de.augmentia.quad.core.hook.pipeline.HookRegistry;
import de.augmentia.quad.core.observability.LoggingHook;
import de.augmentia.quad.core.agent.runtime.IdempotencyStore;
import de.augmentia.quad.core.agent.runtime.OTelAgentTracer;
import de.augmentia.quad.core.agent.runtime.ToolExecutor;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.SubAgentTool;
import de.augmentia.quad.core.tool.ToolArgsMapper;
import dev.langchain4j.model.chat.ChatModel;
import de.augmentia.quad.core.session.CurrentSession;
import de.augmentia.quad.core.session.WorkspaceResolver;

import java.nio.file.Path;
import java.util.ArrayList;
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
    private boolean llmFromEnv = false;
    private Path workspace = Path.of(".");
    private Set<String> toolNames = null;
    private final List<Map.Entry<String, Agent>> subAgents = new ArrayList<>();
    private StructuredOutputConfig structuredOutputConfig;
    
    // Skills
    private final List<Path> skillDirs = new ArrayList<>();
    private List<String> initialSkills = List.of();
    private boolean skillSearchEnabled = false;
    private ChatModel subAgentModel;
    private ChatRequestParameters chatParameters;

    // Canonical config surface (all optional)
    private ChatModel model;
    private String systemPrompt;
    private String userMessageTemplate;
    private String cachePrefix;
    private Integer reActMaxIterations;
    private Integer contextMaxTokens;
    private CurrentSession currentSession;
    private final List<Object> toolObjects = new ArrayList<>();
    private final List<Guardrail> guardrails = new ArrayList<>();
    private final List<AgentHook> hooks = new ArrayList<>();
    
    private AgentBuilder(Class<T> agentClass) {
        this.agentClass = agentClass;
    }

    private AgentBuilder(Class<T> agentClass, T agent) {
        this.agentClass = agentClass;
        this.agent = agent;
    }

    /** Creates a builder for the ready-to-use {@link QuadAgent} default agent. */
    public static AgentBuilder<QuadAgent> defaultAgent() {
        return create(QuadAgent.class);
    }

    /** Creates a new builder for the given agent class. */
    public static <T extends Agent> AgentBuilder<T> create(Class<T> agentClass) {
        return new AgentBuilder<>(agentClass);
    }

    /**
     * Creates a builder around an existing agent instance. Avoids reflection and supports
     * agents that need constructor arguments.
     */
    @SuppressWarnings("unchecked")
    public static <T extends Agent> AgentBuilder<T> of(T agent) {
        if (agent == null) throw new IllegalArgumentException("agent must not be null");
        return new AgentBuilder<>((Class<T>) agent.getClass(), agent);
    }
    
    /**
     * Configures the LLM from environment variables (OPENAI_API_KEY, OPENAI_MODEL).
     *
     * <p>Does not instantiate the agent: the instance is created once in {@link #build()},
     * where the model is actually applied.
     */
    public AgentBuilder<T> withLlmFromEnv() {
        this.llmFromEnv = true;
        return this;
    }

    /** Alias for {@link #withLlmFromEnv()} using consistent "model" naming. */
    public AgentBuilder<T> withModelFromEnv() {
        return withLlmFromEnv();
    }

    /** Sets an explicit {@link ChatModel}; takes precedence over {@link #withLlmFromEnv()}. */
    public AgentBuilder<T> withModel(ChatModel model) {
        this.model = model;
        return this;
    }

    /** Sets the system prompt. */
    public AgentBuilder<T> withSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        return this;
    }

    /** Sets the user message template. */
    public AgentBuilder<T> withUserMessageTemplate(String template) {
        this.userMessageTemplate = template;
        return this;
    }

    /** Sets the shared cache prefix (Block A). */
    public AgentBuilder<T> withCachePrefix(String cachePrefix) {
        this.cachePrefix = cachePrefix;
        return this;
    }

    /** Sets the ReAct round limit ({@code 0} = unlimited). */
    public AgentBuilder<T> withReActMaxIterations(int maxIterations) {
        this.reActMaxIterations = maxIterations;
        return this;
    }

    /** Sets the transcript token budget per round. */
    public AgentBuilder<T> withContextMaxTokens(int maxTokens) {
        this.contextMaxTokens = maxTokens;
        return this;
    }

    /** Registers {@code @Tool} methods from plain objects; no agent subclassing needed. */
    public AgentBuilder<T> withToolObjects(Object... toolObjects) {
        if (toolObjects != null) {
            for (Object o : toolObjects) {
                if (o != null) this.toolObjects.add(o);
            }
        }
        return this;
    }

    /** Adds a guardrail (opt-in; nothing is installed by default). */
    public AgentBuilder<T> withGuardrail(Guardrail guardrail) {
        if (guardrail != null) this.guardrails.add(guardrail);
        return this;
    }

    /** Adds a lifecycle hook. */
    public AgentBuilder<T> withHook(AgentHook hook) {
        if (hook != null) this.hooks.add(hook);
        return this;
    }

    /** Sets the {@link CurrentSession} holder (needed for non-CDI runtimes). */
    public AgentBuilder<T> withCurrentSession(CurrentSession currentSession) {
        this.currentSession = currentSession;
        return this;
    }

    /**
     * Uses an externally built {@link QuadToolRegistry} instead of creating one.
     *
     * <p>Intended for non-CDI runtimes that assemble tools from a catalog (for example the
     * standalone worker). The registry's own {@code registerFromAgent} is still applied, so
     * do not pre-register the agent's own tools into it.</p>
     */
    public AgentBuilder<T> withToolRegistry(QuadToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        return this;
    }

    /**
     * Applies a canonical {@link AgentConfig}. Explicit builder calls made afterwards win.
     */
    public AgentBuilder<T> withConfig(AgentConfig config) {
        if (config == null) return this;
        if (config.model() != null) this.model = config.model();
        if (config.modelFromEnv()) this.llmFromEnv = true;
        if (config.systemPrompt() != null) this.systemPrompt = config.systemPrompt();
        if (config.userMessageTemplate() != null) this.userMessageTemplate = config.userMessageTemplate();
        if (config.cachePrefix() != null) this.cachePrefix = config.cachePrefix();
        if (config.temperature() != null || config.maxOutputTokens() != null) {
            this.chatParameters = config.toChatParameters();
        }
        if (config.maxReActIterations() != null) this.reActMaxIterations = config.maxReActIterations();
        if (config.contextMaxTokens() != null) this.contextMaxTokens = config.contextMaxTokens();
        if (config.structuredOutput() != null) this.structuredOutputConfig = config.structuredOutput();
        if (config.allowedTools() != null && !config.allowedTools().isEmpty()) this.toolNames = config.allowedTools();
        if (config.skillDirs() != null) this.skillDirs.addAll(config.skillDirs());
        if (config.initialSkills() != null) this.initialSkills = config.initialSkills();
        this.skillSearchEnabled = config.skillSearchEnabled() || this.skillSearchEnabled;
        if (config.workspace() != null) this.workspace = config.workspace();
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
    
    /** Applies the standard defaults: logging and the built-in PII guardrail. */
    public AgentBuilder<T> withDefaults() {
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

        if (model != null) {
            agent.setLlm(model);
        } else if (llmFromEnv && agent.getLlm() == null) {
            agent.setLlm(ModelFactory.createOpenAiFromEnv());
        }

        if (toolRegistry == null) {
            toolRegistry = new QuadToolRegistry(new ToolArgsMapper(new ObjectMapper()));
        }
        
        // Register tools from agent
        toolRegistry.registerFromAgent(agent);
        // ... and from plain tool objects (no subclassing required)
        for (Object toolObject : toolObjects) {
            toolRegistry.registerFromObject(toolObject);
        }
        
        // Filter to specific tool names if configured
        if (toolNames != null && !toolNames.isEmpty()) {
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
        if (workspace != null) {
            agent.setWorkspaceResolver(new WorkspaceResolver().baseWorkspace(workspace));
        }
        if (currentSession != null) {
            agent.setCurrentSession(currentSession);
        }

        // ToolExecutor (idempotency + saga + hooks + events) if not already set
        if (agent.getToolExecutor() == null) {
            agent.setToolExecutor(new ToolExecutor(
                new IdempotencyStore(), new OTelAgentTracer(), eventPublisher, hookRegistry));
        }

        // Per-agent configuration (all optional)
        if (systemPrompt != null) agent.setSystemPrompt(systemPrompt);
        if (cachePrefix != null) agent.setCachePrefix(cachePrefix);
        if (userMessageTemplate != null) agent.setUserMessageTemplate(userMessageTemplate);
        if (reActMaxIterations != null) agent.setReActMaxIterations(reActMaxIterations);
        if (contextMaxTokens != null) agent.setContextMaxTokens(contextMaxTokens);

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

        // Opt-in guardrails and hooks
        for (Guardrail guardrail : guardrails) {
            agent.addHook(new GuardrailPlugin(List.of(guardrail), List.of()));
        }
        for (AgentHook hook : hooks) {
            agent.addHook(hook);
        }

        // Register skill plugins if skill directories configured
        if (!skillDirs.isEmpty()) {
            AgentAssembler.registerSkills(agent, toolRegistry, skillDirs, initialSkills, skillSearchEnabled);
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
}