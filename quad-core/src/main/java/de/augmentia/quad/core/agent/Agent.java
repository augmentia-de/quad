package de.augmentia.quad.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.capability.skill.SkillRegistry;
import de.augmentia.quad.core.capability.prompt.ToolListRenderer;
import de.augmentia.quad.core.capability.prompt.SkillListRenderer;
import de.augmentia.quad.core.config.ModelFactory;
import de.augmentia.quad.core.config.StructuredOutputConfig;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import de.augmentia.quad.core.agent.channels.ChannelManager;
import de.augmentia.quad.core.capability.context.ContextManager;
import de.augmentia.quad.core.hook.pipeline.AgentHook;
import de.augmentia.quad.core.hook.pipeline.HookRegistry;
import de.augmentia.quad.core.capability.doc.AgentDocExtractor;
import de.augmentia.quad.core.agent.runtime.AgentEventPublisher;
import de.augmentia.quad.core.agent.runtime.AgentRuntime;
import de.augmentia.quad.core.capability.CapabilitySearchRouter;
import de.augmentia.quad.core.agent.runtime.OTelAgentTracer;
import de.augmentia.quad.core.agent.runtime.SandboxClient;
import de.augmentia.quad.core.agent.runtime.ToolExecutor;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
import de.augmentia.quad.core.session.WorkspaceResolver;
import de.augmentia.quad.core.session.memory.ConversationManager;
import de.augmentia.quad.core.session.memory.SummarizingSlidingWindowConversationManager;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.ToolArgsMapper;
import de.augmentia.quad.core.tool.ToolMethod;
import de.augmentia.quad.core.tool.SubAgentTool;
import de.augmentia.quad.core.tool.DynamicSubAgentTool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.inject.Inject;

import java.util.List;

public abstract class Agent {
    @Inject protected ChatModel llm;
    @Inject protected CapabilitySearchRouter router;
    @Inject protected SandboxClient sandbox;
    @Inject protected OTelAgentTracer tracer;
    @Inject protected QuadToolRegistry toolRegistry;
    @Inject protected ToolExecutor toolExecutor;
    @Inject protected AgentDocExtractor docExtractor;
    @Inject protected SessionStateFactory stateFactory;
    @Inject protected CurrentSession currentSession;
    @Inject protected HookRegistry hookRegistry;
    @Inject protected AgentEventPublisher eventPublisher;
    @Inject protected ChannelManager channelManager;
    @Inject protected ContextManager contextManager;
    @Inject protected SkillRegistry skillRegistry;
    @Inject protected WorkspaceResolver workspaceResolver;
    protected ToolListRenderer toolListRenderer = new ToolListRenderer();
    protected SkillListRenderer skillListRenderer = new SkillListRenderer();

    // ── Delegates (initialized lazily after CDI injection) ──
    private PromptAssembler promptAssembler;
    private SessionFacade sessionFacade;
    private HitlFacade hitlFacade = new HitlFacade();

    // ── Configuration ──
    private StructuredOutputConfig structuredOutputConfig;
    private ChatRequestParameters chatParameters;
    private String systemPrompt;
    private String cachePrefix;
    private String userMessageTemplate;
    private boolean jsonInput;
    private int reactMaxIterations = 10;

    // ── Delegate initialization (called on first use, after CDI injection) ──

    private PromptAssembler promptAssembler() {
        if (promptAssembler == null) {
            promptAssembler = new PromptAssembler(docExtractor, toolRegistry, skillRegistry,
                    contextManager, toolListRenderer, skillListRenderer, workspaceResolver);
        }
        return promptAssembler;
    }

    private SessionFacade sessionFacade() {
        if (sessionFacade == null) {
            sessionFacade = new SessionFacade(currentSession);
        }
        return sessionFacade;
    }

    // ── Configuration getters/setters ──

    /**
     * Token budget the transcript is written back into before every model call.
     *
     * <p>Acts as the upper bound for the {@link SummarizingSlidingWindowConversationManager}
     * that {@link #conversationManager()} builds. Together with {@code reactMaxIterations == 0}
     * this is the condition under which unlimited rounds stay affordable: the transcript does
     * not grow with the round count.
     */
    private static final int DEFAULT_CONTEXT_MAX_TOKENS = 8_000;

    /** How many tokens the transcript may claim per round at most. */
    private int contextMaxTokens = DEFAULT_CONTEXT_MAX_TOKENS;

    /** How many user blocks at the end remain untouched. */
    private int contextKeepLastUserMessages = 4;

    /**
     * {@code null} means: build the default on the first run. Deliberately not pre-populated,
     * because the default needs a {@code ChatModel} and {@link #llm} is only reliably present
     * at runtime.
     */
    private ConversationManager conversationManager;

    /**
     * Bounds the transcript that goes to the model per round.
     *
     * <p><b>The default is not {@code null}.</b> {@link AgentRuntime#runReActLoop} explicitly
     * bases the round limit {@code reactMaxIterations == 0} in its javadoc on
     * {@code limitContext} writing the transcript back every round. That wiring was missing at
     * all three constructor sites, so {@code limitContext} returned early and {@code 0} meant:
     * unlimited rounds <em>and</em> unlimited context. The claim in the javadoc held only for
     * the direct {@code AgentRuntime} constructor path, that is, for the tests.
     *
     * <p><b>Why {@link SummarizingSlidingWindowConversationManager} and not the
     * {@link de.augmentia.quad.core.session.memory.SlidingWindowConversationManager}.</b> The
     * simple manager cuts bluntly from the back. In a tool loop that means: the cut can land
     * in the middle of a request-result pair, and the model gets a {@code ToolExecutionResult}
     * without a matching {@code AiMessage}. It reads that as incomplete and hallucinates the
     * request to go with it. The summarising manager instead anchors on message boundaries and
     * falls back to a position boundary that reaches back to the start of an AI answer. It
     * costs one model call, but only when something really has to be trimmed.
     *
     * @param manager the rule, or {@code null} for the default
     */
    public void setConversationManager(ConversationManager manager) {
        this.conversationManager = manager;
    }

    public ConversationManager getConversationManager() {
        return conversationManager();
    }

    /**
     * How many user blocks remain untouched at the end. Only effective if no own
     * {@link ConversationManager} is set.
     */
    public void setContextKeepLastUserMessages(int keep) {
        this.contextKeepLastUserMessages = keep;
        this.conversationManager = null; // rebuild, otherwise the old rule wins
    }

    /**
     * Upper bound for the transcript that goes to the model per round.
     *
     * <p>This is the quantity that determines the cost: for unlimited rounds the calculation
     * is {@code rounds times tokens per round}, and only this factor is in our hands. A value of
     * 8,000 lets an agent work well and limits one that would otherwise run into a cycle
     * to a third of the volume.
     *
     * <p>Takes effect only after {@link #setConversationManager} with {@code null} or before the first run,
     * because the default is built on the first run.
     */
    public void setContextMaxTokens(int maxTokens) {
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be >= 1, got: " + maxTokens);
        }
        this.contextMaxTokens = maxTokens;
        this.conversationManager = null; // rebuild, otherwise the old rule wins
    }

    public int getContextMaxTokens() {
        return contextMaxTokens;
    }

    /**
     * The manager that this agent's runs use.
     *
     * <p>Deliberately lazy: {@link SummarizingSlidingWindowConversationManager} needs a
     * {@code ChatModel}, and that is {@link #llm} here. The agent is not necessarily built with
     * a model (tests, {@link #executeBash} paths, provider selection), so it is created
     * the default only when a run actually happens and {@code llm} is not null.
     */
    private ConversationManager conversationManager() {
        if (conversationManager != null) {
            return conversationManager;
        }
        if (llm == null) {
            return null; // without a model there is nothing to limit
        }
        conversationManager = new SummarizingSlidingWindowConversationManager(
                llm, contextMaxTokens, contextKeepLastUserMessages);
        return conversationManager;
    }

    public void setStructuredOutputConfig(StructuredOutputConfig config) {
        this.structuredOutputConfig = config;
    }

    public StructuredOutputConfig getStructuredOutputConfig() {
        return structuredOutputConfig;
    }

    public void setChatParameters(ChatRequestParameters params) {
        this.chatParameters = params;
    }

    public ChatRequestParameters getChatParameters() {
        return chatParameters;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setCachePrefix(String cachePrefix) {
        this.cachePrefix = cachePrefix;
        propagateCachePrefixToSubAgents();
    }

    public String getCachePrefix() {
        return cachePrefix;
    }

    /**
     * Propagates this agent's active cache prefix to its registered sub-agent
     * tools (agent-scoped prefix chain, §6). Static sub-agents inherit via their
     * {@link Agent} field; dynamic sub-agents inherit when
     * {@code inheritsCachePrefix()} is true (default). A null/blank prefix
     * propagates as the cleared state for dynamic sub-agents; static sub-agents
     * are only touched when they have no explicit prefix of their own.
     */
    private void propagateCachePrefixToSubAgents() {
        if (toolRegistry == null) return;
        for (ToolMethod tool : toolRegistry.getAll()) {
            if (tool instanceof SubAgentTool sub) {
                Agent subAgent = sub.getSubAgent();
                if (subAgent != null && subAgent.getCachePrefix() == null
                        && cachePrefix != null && !cachePrefix.isBlank()) {
                    subAgent.setCachePrefix(cachePrefix);
                }
            } else if (tool instanceof DynamicSubAgentTool dynamic) {
                if (dynamic.inheritsCachePrefix()
                        && cachePrefix != null && !cachePrefix.isBlank()
                        && !java.util.Objects.equals(dynamic.getCachePrefix(), cachePrefix)) {
                    dynamic.setCachePrefix(cachePrefix);
                }
            }
        }
    }

    public void setUserMessageTemplate(String userMessageTemplate) {
        this.userMessageTemplate = userMessageTemplate;
    }

    public String getUserMessageTemplate() {
        return userMessageTemplate;
    }

    public void setJsonInput(boolean jsonInput) {
        this.jsonInput = jsonInput;
    }

    public boolean isJsonInput() {
        return jsonInput;
    }

    /**
     * The limit of the ReAct rounds.
     *
     * <p><b>{@code 0} means unlimited.</b> That is only meaningful because the
     * context is bounded as it grows rather than cut away: the run acquires a token
     * budget and summarises the older history every round. Without it, "unlimited" is
     * not a gain but growing memory and latency cost.
     *
     * <p>Negative stays invalid — a negative limit is a configuration error and
     * not a wish for infinity.
     */
    public void setReActMaxIterations(int maxIterations) {
        if (maxIterations < 0) throw new IllegalArgumentException(
                "maxIterations must be >= 0 (0 = unlimited), got: " + maxIterations);
        this.reactMaxIterations = maxIterations;
    }

    // ── DI setters ──

    protected boolean requiresDynamicDiscovery() { return false; }

    protected abstract AgentSessionState newSessionState();

    public AgentSessionState createSessionState() {
        return newSessionState();
    }

    public void initLlm() {
        this.llm = ModelFactory.createOpenAiFromEnv();
    }

    public void initLlm(String apiKey) {
        this.llm = ModelFactory.createOpenAiFromEnv(apiKey);
    }

    public void setLlm(ChatModel llm) {
        this.llm = llm;
    }

    public ChatModel getLlm() {
        return llm;
    }

    public void setWorkspaceResolver(WorkspaceResolver workspaceResolver) {
        this.workspaceResolver = workspaceResolver;
    }

    public void setCurrentSession(CurrentSession currentSession) {
        this.currentSession = currentSession;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public void setSandbox(SandboxClient sandbox) {
        this.sandbox = sandbox;
    }

    public void setStateFactory(SessionStateFactory factory) { this.stateFactory = factory; }

    public void setToolRegistry(QuadToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public QuadToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public void setToolExecutor(ToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
    }

    public ToolExecutor getToolExecutor() {
        return toolExecutor;
    }

    public void setDocExtractor(AgentDocExtractor docExtractor) {
        this.docExtractor = docExtractor;
    }

    public void setHookRegistry(HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    public HookRegistry getHookRegistry() {
        return hookRegistry;
    }

    public void setEventPublisher(AgentEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public AgentEventPublisher getEventPublisher() {
        return eventPublisher;
    }

    public void setChannelManager(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    public ChannelManager getChannelManager() {
        return channelManager;
    }

    public void setContextManager(ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    public ContextManager getContextManager() {
        return contextManager;
    }

    public void setAttribute(String name, Object value) {
        // Placeholder for attribute setting - implementations vary by framework
    }

    // ── HITL delegation ──

    public void pauseExecution() {
        hitlFacade.pauseExecution();
    }

    public void approve() {
        hitlFacade.approve();
    }

    public void reject(String reason) {
        hitlFacade.reject(reason);
    }

    public boolean isPaused() {
        return hitlFacade.isPaused();
    }

    public String pauseReason() {
        return hitlFacade.pauseReason();
    }

    // ── Hook/tool facade ──

    public void addHook(AgentHook hook) {
        if (hookRegistry == null) hookRegistry = new HookRegistry();
        hookRegistry.register(hook);
    }

    public void removeHook(String name) {
        if (hookRegistry != null) hookRegistry.unregister(name);
    }

    public String executeTool(String toolName, String jsonArguments, AgentSessionState state) {
        if (toolRegistry == null) return "Error: No tool registry configured";
        if (toolExecutor == null) return "Error: No tool executor configured";

        ToolMethod tool = toolRegistry.get(toolName);
        if (tool == null) return "Error: Tool not found: " + toolName;

        return toolExecutor.execute(tool, jsonArguments, state).text();
    }

    // ── CWD delegation ──

    public String currentCwd() {
        return sessionFacade().currentCwd();
    }

    public void setCwd(String cwd) {
        sessionFacade().setCwd(cwd);
    }

    public void pushCwd(String cwd) {
        sessionFacade().pushCwd(cwd);
    }

    public void popCwd() {
        sessionFacade().popCwd();
    }

    public void resetCwd() {
        sessionFacade().resetCwd();
    }

    public String cwdRoot() {
        return sessionFacade().cwdRoot();
    }

    public int cwdDepth() {
        return sessionFacade().cwdDepth();
    }

    // ── Execution entry points ──

    public final String run(String prompt) {
        return run(prompt, stateFactory != null ? stateFactory.create(this) : newSessionState());
    }

    public String run(String prompt, AgentSessionState state) {
        sessionFacade().bindSession(state);
        requireLlm();
        return createRuntime().run(this, prompt, state);
    }

    /**
     * @deprecated use {@link #run(String)} — kept as a compatibility alias.
     */
    @Deprecated
    public final String execute(String prompt) {
        return run(prompt);
    }

    /**
     * @deprecated use {@link #run(String, AgentSessionState)} — kept as a compatibility alias.
     */
    @Deprecated
    public String execute(String prompt, AgentSessionState state) {
        return run(prompt, state);
    }

    public final AgentResult executeStructured(String prompt) {
        return executeStructured(prompt, stateFactory != null ? stateFactory.create(this) : newSessionState());
    }

    public AgentResult executeStructured(String prompt, AgentSessionState state) {
        sessionFacade().bindSession(state);
        requireLlm();
        return createRuntime().runStructured(this, prompt, state);
    }

    public AgentResult executeStructured(String systemMessage, String userMessage, AgentSessionState state) {
        sessionFacade().bindSession(state);
        requireLlm();
        return createRuntime().runStructured(this, systemMessage, userMessage, state);
    }

    public String executeBash(String script) {
        return sandbox != null ? sandbox.run(script) : "sandbox error";
    }

    /**
     * @deprecated use {@link #run(String)} — kept as a compatibility alias.
     */
    @Deprecated
    public String executeReAct(String prompt) {
        return run(prompt);
    }

    /**
     * @deprecated use {@link #run(String, AgentSessionState)} — kept as a compatibility alias.
     */
    @Deprecated
    public String executeReAct(String prompt, AgentSessionState state) {
        return run(prompt, state);
    }

    /**
     * Fails fast when no model is configured, instead of silently returning a marker
     * string that a caller can easily overlook.
     */
    private void requireLlm() {
        if (llm == null) {
            throw new IllegalStateException(
                    "No ChatModel configured. Call setLlm(...)/initLlm(...), or build the agent via "
                    + "AgentBuilder/QuadAgent.create()/Agents.simple().");
        }
    }

    /**
     * Builds the runtime for one run. Single place that wires the transcript bound and
     * structured-output config, so the three entry points cannot drift apart.
     *
     * <p>Without {@code withConversationManager(...)} limitContext goes into the void: the
     * manager is otherwise null and the transcript grows untrimmed until the round limit.
     * See {@link #setConversationManager}.
     */
    private AgentRuntime createRuntime() {
        ensureToolRegistry();
        AgentRuntime runtime = new AgentRuntime(llm, toolRegistry, null, reactMaxIterations,
                hookRegistry, eventPublisher, tracer, toolExecutor, channelManager);
        runtime.withConversationManager(conversationManager());
        runtime.setStructuredOutputConfig(structuredOutputConfig);
        return runtime;
    }

    /**
     * Registers this agent's own {@code @Tool} methods when no registry was injected or set
     * programmatically. Without this a bare {@code new MyAgent()} silently loses all tools.
     */
    private void ensureToolRegistry() {
        if (toolRegistry == null) {
            toolRegistry = new QuadToolRegistry(new ToolArgsMapper(new ObjectMapper()));
            toolRegistry.registerFromAgent(this);
        }
    }

    // ── Prompt assembly delegation ──

    public List<ChatMessage> initialMessages(String prompt, AgentSessionState state) {
        String jsonOutputSchema = structuredOutputConfig != null ? structuredOutputConfig.effectiveSchema() : null;
        return promptAssembler().buildInitialMessages(prompt, state, systemPrompt, jsonInput, userMessageTemplate,
                jsonOutputSchema, cachePrefix);
    }

    public List<ToolSpecification> toolSpecifications() {
        return toolRegistry != null
                ? toolRegistry.getSpecifications().stream().map(s -> (ToolSpecification) s).toList()
                : List.of();
    }
}
