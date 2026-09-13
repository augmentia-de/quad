package de.augmentia.quad.quarkus.messaging;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.config.StructuredOutputConfig;
import de.augmentia.quad.core.agent.SessionStateFactory;
import de.augmentia.quad.core.config.TieredModelConfig;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.InMemorySessionManager;
import de.augmentia.quad.core.session.memory.SessionMemory;
import de.augmentia.quad.core.tool.StandardToolProvider;
import de.augmentia.quad.core.tool.ToolMethod;
import de.augmentia.quad.core.guardrails.GuardrailPlugin;
import de.augmentia.quad.core.gdpr.GdprAgentPlugin;
import de.augmentia.quad.core.gdpr.PiiAnonymizerHook;
import de.augmentia.quad.quarkus.agent.AgentBuilderFactory;
import de.augmentia.quad.quarkus.agent.hook.HookRegistry;
import de.augmentia.quad.quarkus.agent.config.TieredChatModel;
import de.augmentia.quad.quarkus.guardrails.GuardrailFactory;
import de.augmentia.quad.quarkus.mcp.McpManagerService;
import de.augmentia.quad.quarkus.ui.DemoAgent;
import de.augmentia.quad.quarkus.ui.SharedState;
import de.augmentia.quad.quarkus.ui.AgentBean;
import de.augmentia.quad.quarkus.ui.UiConfig;
import de.augmentia.quad.quarkus.contract.ApiDtos;
import de.augmentia.quad.quarkus.persistence.JournalService;
import de.augmentia.quad.quarkus.persistence.AgentStepExporter;
import de.augmentia.quad.quarkus.hitl.HitlService;
import de.augmentia.quad.core.capability.CapabilitySearchAgent;
import de.augmentia.quad.quarkus.permission.PermissionService;
import de.augmentia.quad.quarkus.skillstore.JdbcSkillStore;
import de.augmentia.quad.quarkus.skillstore.SkillsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class ChannelAgentFactory {

    private static final Logger log = Logger.getLogger(ChannelAgentFactory.class);

    @Inject SharedState state;
    @Inject UiConfig uiConfig;
    @Inject McpManagerService mcpManager;
    @Inject JournalService journalService;
    @Inject AgentStepExporter agentStepExporter;
    @Inject SessionStateFactory stateFactory;
    @Inject AgentBuilderFactory agentBuilderFactory;
    @Inject StandardToolProvider standardToolProvider;
    @Inject PermissionService permissionService;
    @Inject SkillsConfig skillStoreConfig;
    @Inject JdbcSkillStore jdbcSkillStore;
    @Inject HitlService hitlService;
    @Inject HookRegistry hookRegistry;

    @ConfigProperty(name = "quad.model.tiers.enabled", defaultValue = "true")
    boolean modelTiersEnabled;

    @ConfigProperty(name = "quad.gdpr.enabled", defaultValue = "false")
    boolean gdprEnabled;

    @ConfigProperty(name = "quad.gdpr.replacement", defaultValue = "[REDACTED]")
    String gdprReplacement;

    @ConfigProperty(name = "quad.memory.max-messages", defaultValue = "50")
    int memoryMaxMessages;

    @ConfigProperty(name = "quad.skills.enabled", defaultValue = "false")
    boolean skillsEnabled;

    @ConfigProperty(name = "quad.skills.dir", defaultValue = "")
    Optional<String> skillsDir;

    @ConfigProperty(name = "quad.skills.initial", defaultValue = "")
    Optional<String> initialSkills;

    @ConfigProperty(name = "quad.skills.search", defaultValue = "false")
    boolean skillSearchEnabled;

    public Agent create(String agentId) {
        ApiDtos.AgentDefinition def = state.agents().get(agentId);
        if (def == null) {
            throw new IllegalArgumentException(
                "Agent not found: " + agentId + ". Create via POST /api/ui/agents first.");
        }
        return buildAgent(def);
    }

    /**
     * Builds an agent from an ad-hoc definition without registering it in
     * {@link SharedState}. Used to construct dedicated, application-internal agents (e.g. the
     * workflow Master-Orchestrator) with their own system prompt and tools, on top of the single
     * base agent — specific agents are created without a template.
     */
    public Agent buildFromDefinition(ApiDtos.AgentDefinition def) {
        return buildAgent(def);
    }

    public String process(String agentId, String payload) {
        Agent agent = create(agentId);
        AgentSessionState sessionState = newRunState();
        return agent.execute(payload, sessionState);
    }

    /**
     * Per-agent runtime limit in milliseconds (from def.timeoutSeconds), or 0
     * if the agent has no individual limit defined. Callers (e.g.
     * AgentNodeExecutor) fall back to the global default.
     */
    public long timeoutMsOrZero(String agentId) {
        if (agentId == null || agentId.isBlank()) return 0;
        ApiDtos.AgentDefinition def = state.agents().get(agentId);
        if (def != null && def.timeoutSeconds != null && def.timeoutSeconds > 0) {
            return def.timeoutSeconds * 1000L;
        }
        return 0;
    }

    private Agent buildAgent(ApiDtos.AgentDefinition def) {
        // 1. Tools from definition or config defaults
        Set<String> tools = new HashSet<>();
        if (def.tools != null && def.tools.length > 0) {
            tools.addAll(Set.of(def.tools));
        } else if (def.tools == null) {
            // tools not specified at all → use global defaults.
            tools.addAll(uiConfig.getEnabledTools());
        }
        // def.tools == [] (explicitly empty) → agent deliberately has NO tools.

        // 2. Load MCP tools
        List<ToolMethod> mcpTools = mcpManager.tools() != null ? mcpManager.tools() : List.of();

        if (uiConfig.isDummyMode()) {
            var builder = applySkills(AgentBuilder.create(DemoAgent.class).withDefaults());
            builder = builder.withTools(tools);
            var agent = builder.withLogging().build();
            registerTools(agent, mcpTools, tools);
            applyPluginsAndHooks(agent, def);
            return agent;
        }

        // 3. Create agent via AgentBuilderFactory (no withTools — we handle that ourselves)
        Class<? extends Agent> agentClass = resolveAgentType(def.agentType);
        AgentBuilder<?> builder = agentBuilderFactory.forClass(agentClass);
        builder = applySkills(builder.withTools(tools));
        var agent = builder.withLogging().build();
        if (modelTiersEnabled) {
            agent.setLlm(new TieredChatModel(
                TieredModelConfig.fromEnv()));
        }
        agent.setStateFactory(stateFactory);

        // 4. Register only MCP tools that are in the filter
        registerTools(agent, mcpTools, tools);

        // 5. Plugins, guardrails, chat config
        applyPluginsAndHooks(agent, def);
        applyAgentMessageConfig(agent, def);
        applyDbSkills(agent, tools);
        return agent;
    }

    private void registerTools(Agent agent, List<ToolMethod> mcpTools, Set<String> allowedTools) {
        java.util.Set<String> allowedLower = new HashSet<>();
        for (String t : allowedTools) {
            allowedLower.add(t.toLowerCase(java.util.Locale.ROOT));
        }
        for (ToolMethod tm : standardToolProvider.getTools()) {
            if (!tm.spec().name().startsWith("mcp_")
                    && allowedLower.contains(tm.spec().name().toLowerCase(java.util.Locale.ROOT))) {
                agent.getToolRegistry().register(tm.spec().name(), tm);
            }
        }
        for (ToolMethod tm : mcpTools) {
            if (allowedLower.contains(tm.spec().name().toLowerCase(java.util.Locale.ROOT))) {
                agent.getToolRegistry().register(tm.spec().name(), tm);
            }
        }
    }

    private void applyPluginsAndHooks(Agent agent, ApiDtos.AgentDefinition def) {
        if (journalService.isEnabled()) {
            agent.getEventPublisher().setJournalExporter(journalService.exporter());
        }
        agent.getEventPublisher().addEventListener(agentStepExporter);
        if (gdprEnabled) {
            var gdpr = new GdprAgentPlugin(new InMemorySessionManager(),
                java.util.EnumSet.allOf(PiiAnonymizerHook.MaskType.class),
                PiiAnonymizerHook.BlockAction.REDACT, gdprReplacement, null);
            gdpr.initAgent(agent);
        }
        if (permissionService.enabled()) {
            if (hitlService != null && hitlService.checkpointService() != null) {
                permissionService.attachCheckpointService(hitlService.checkpointService());
            }
            permissionService.applyGuard(agent);
        }
        if (def != null) {
            for (var plugin : hookRegistry.fromNames(def.hooks)) {
                plugin.initAgent(agent);
                agent.addHook(plugin);
            }
        }
        if (def != null) {
            var inputGuards = GuardrailFactory.fromNames(def.guardrailsInput);
            var outputGuards = GuardrailFactory.fromNames(def.guardrailsOutput);
            if (!inputGuards.isEmpty() || !outputGuards.isEmpty()) {
                agent.addHook(new GuardrailPlugin(inputGuards, outputGuards));
            }
        }
    }

    private void applyAgentMessageConfig(Agent agent, ApiDtos.AgentDefinition def) {
        if (def == null) return;
        if (def.systemPrompt != null && !def.systemPrompt.isBlank()) {
            agent.setSystemPrompt(def.systemPrompt);
        }
        if (def.userMessageTemplate != null && !def.userMessageTemplate.isBlank()) {
            agent.setUserMessageTemplate(def.userMessageTemplate);
        }
        if (def.jsonOutput) {
            // Eingabeseite: JSON-Template-Rendering (userMessageTemplate) bleibt aktiv.
            agent.setJsonInput(true);
        }
        if (def.jsonOutputSchema != null && !def.jsonOutputSchema.isBlank()) {
            // Ausgabeseite: strukturierte JSON-Ausgabe via responseFormat erzwungen.
            agent.setStructuredOutputConfig(StructuredOutputConfig.dynamicSchema(def.jsonOutputSchema));
        }
        if (def.chatParameters != null && !def.chatParameters.isBlank()) {
            try {
                var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(def.chatParameters);
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
                agent.setChatParameters(builder.build());
            } catch (Exception e) {
                log.warnf(e, "Failed to parse chatParameters for agent %s", agent.getClass().getSimpleName());
            }
        } else {
            var builder = dev.langchain4j.model.chat.request.DefaultChatRequestParameters.builder();
            boolean hasAny = false;
            if (def.temperature > 0) { builder.temperature(def.temperature); hasAny = true; }
            if (def.maxTokens > 0) { builder.maxOutputTokens(def.maxTokens); hasAny = true; }
            if (def.topP > 0) { builder.topP(def.topP); hasAny = true; }
            if (hasAny) agent.setChatParameters(builder.build());
        }
    }

    private Class<? extends Agent> resolveAgentType(String agentType) {
        if (agentType == null || agentType.isBlank()) agentType = "ua";
        return switch (agentType.toLowerCase()) {
            case "ua", "ui", "" -> AgentBean.class;
            case "capability" -> CapabilitySearchAgent.class;
            case "demo" -> DemoAgent.class;
            default -> AgentBean.class;
        };
    }

    private AgentBuilder<?> applySkills(AgentBuilder<?> builder) {
        if (!skillsEnabled) return builder;
        java.nio.file.Path dir = skillsDir.filter(s -> !s.isBlank())
            .map(java.nio.file.Path::of)
            .orElse(java.nio.file.Path.of("skills"));
        builder.withSkills(dir);
        if (skillSearchEnabled) builder.withSkillSearch(true);
        initialSkills.filter(s -> !s.isBlank()).ifPresent(s -> builder.withInitialSkills(
            Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList()));
        return builder;
    }

    /** Port 06: union-filter agent's tool registry by DB skill whitelist. */
    private void applyDbSkills(Agent agent, Set<String> allowedTools) {
        if (!skillStoreConfig.isEnabled()) return;
        try {
            var reg = agent.getToolRegistry();
            if (reg == null) return;
            Set<String> currentNames = reg.getAll().stream()
                .map(t -> t.spec().name()).collect(Collectors.toSet());
            
            // Union: collect all tools allowed by any DB skill that has restrictions
            Set<String> filtered = new HashSet<>(currentNames);
            for (var skill : jdbcSkillStore.listAll()) {
                if (skill.hasToolRestrictions()) {
                    Set<String> allowed = skill.filterAllowed(currentNames);
                    filtered.addAll(allowed);
                }
            }
            
            // Remove tools not in the filtered set
            if (!filtered.equals(currentNames)) {
                for (var name : currentNames) {
                    if (!filtered.contains(name)) {
                        reg.remove(name);
                    }
                }
            }
        } catch (Exception e) {
            log.warnf(e, "DB Skills: failed to load/filter — continuing with file-based skills");
        }
    }

    private AgentSessionState newRunState() {
        AgentSessionState s = new AgentSessionState();
        s.setMemory(new SessionMemory(memoryMaxMessages));
        return s;
    }
}
