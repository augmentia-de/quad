package de.augmentia.quad.core.agent;

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
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.ToolMethod;
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

    public void setReActMaxIterations(int maxIterations) {
        if (maxIterations <= 0) throw new IllegalArgumentException("maxIterations must be > 0");
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
        if (llm == null) return "[LLM not configured]";
        AgentRuntime runtime = new AgentRuntime(llm, toolRegistry, null, reactMaxIterations,
                hookRegistry, eventPublisher, tracer, toolExecutor, channelManager);
        runtime.setStructuredOutputConfig(structuredOutputConfig);
        return runtime.run(this, prompt, state);
    }

    public final String execute(String prompt) {
        return run(prompt);
    }

    public String execute(String prompt, AgentSessionState state) {
        return run(prompt, state);
    }

    public final AgentResult executeStructured(String prompt) {
        return executeStructured(prompt, stateFactory != null ? stateFactory.create(this) : newSessionState());
    }

    public AgentResult executeStructured(String prompt, AgentSessionState state) {
        sessionFacade().bindSession(state);
        if (llm == null) return new AgentResult("[LLM not configured]", null, 0);
        AgentRuntime runtime = new AgentRuntime(llm, toolRegistry, null, reactMaxIterations,
                hookRegistry, eventPublisher, tracer, toolExecutor, channelManager);
        runtime.setStructuredOutputConfig(structuredOutputConfig);
        return runtime.runStructured(this, prompt, state);
    }

    public AgentResult executeStructured(String systemMessage, String userMessage, AgentSessionState state) {
        sessionFacade().bindSession(state);
        if (llm == null) return new AgentResult("[LLM not configured]", null, 0);
        AgentRuntime runtime = new AgentRuntime(llm, toolRegistry, null, reactMaxIterations,
                hookRegistry, eventPublisher, tracer, toolExecutor, channelManager);
        runtime.setStructuredOutputConfig(structuredOutputConfig);
        return runtime.runStructured(this, systemMessage, userMessage, state);
    }

    public String executeBash(String script) {
        return sandbox != null ? sandbox.run(script) : "sandbox error";
    }

    public String executeReAct(String prompt) {
        return executeReAct(prompt, stateFactory != null ? stateFactory.create(this) : newSessionState());
    }

    public String executeReAct(String prompt, AgentSessionState state) {
        return run(prompt, state);
    }

    // ── Prompt assembly delegation ──

    public List<ChatMessage> initialMessages(String prompt, AgentSessionState state) {
        String jsonOutputSchema = structuredOutputConfig != null ? structuredOutputConfig.effectiveSchema() : null;
        return promptAssembler().buildInitialMessages(prompt, state, systemPrompt, jsonInput, userMessageTemplate,
                jsonOutputSchema);
    }

    private List<ToolSpecification> toolSpecifications() {
        return toolRegistry != null
                ? toolRegistry.getSpecifications().stream().map(s -> (ToolSpecification) s).toList()
                : List.of();
    }
}
