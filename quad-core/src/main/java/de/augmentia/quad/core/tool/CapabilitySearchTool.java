package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.augmentia.quad.core.capability.Capability;
import de.augmentia.quad.core.capability.CapabilitySearch;
import de.augmentia.quad.core.capability.CapabilitySearchAgent;
import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.capability.skill.SkillRegistry;
import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;
import java.util.Map;

public class CapabilitySearchTool implements ToolMethod {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_TOP_K = 20;
    private static final int DEFAULT_MAX_TOOLS = 3;

    private final CapabilitySearch capabilitySearch;
    private final CapabilitySearchAgent searchAgent;
    private final SkillRegistry skillRegistry;
    private final int defaultTopK;
    private final int maxTools;
    private final ToolSpecification spec;

    public CapabilitySearchTool(CapabilitySearch capabilitySearch, 
                                 ChatModel chatModel,
                                 SkillRegistry skillRegistry) {
        this(capabilitySearch, 
             chatModel != null ? new CapabilitySearchAgent(chatModel) : null,
             skillRegistry,
             DEFAULT_TOP_K,
             DEFAULT_MAX_TOOLS);
    }

    public CapabilitySearchTool(CapabilitySearch capabilitySearch,
                                 CapabilitySearchAgent searchAgent,
                                 SkillRegistry skillRegistry,
                                 int defaultTopK,
                                 int maxTools) {
        this.capabilitySearch = capabilitySearch;
        this.searchAgent = searchAgent;
        this.skillRegistry = skillRegistry;
        this.defaultTopK = defaultTopK;
        this.maxTools = maxTools > 0 ? maxTools : DEFAULT_MAX_TOOLS;
        this.spec = ToolSpecification.builder()
                .name(BaseToolNames.CAPABILITY_SEARCH)
                .description("Analyzes a task and recommends matching tools and skills. "
                    + "Returns structured JSON with task analysis, recommended tools (max " + maxTools + "), skills, and reasoning.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("task", "A task description to find matching tools/skills for")
                        .addStringProperty("tenant", "Optional tenant ID for multi-tenant filtering")
                        .required("task")
                        .build())
                .build();
    }

    @Override
    public ToolSpecification spec() {
        return spec;
    }

    @Override
    public ToolResult execute(String jsonArguments, AgentSessionState state) throws Exception {
        String task = extractTask(jsonArguments);
        if (task == null || task.isBlank()) {
            return ToolResult.success(errorJson("Please provide a 'task' describing what you want to do."));
        }

        String tenantId = extractTenant(jsonArguments);
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = state != null ? state.getTenantId() : "default";
        }

        List<Capability> candidates = List.of();
        if (capabilitySearch != null) {
            candidates = capabilitySearch.search(task, defaultTopK, tenantId);
        }

        Map<String, Skill> skills = Map.of();
        if (skillRegistry != null) {
            skills = skillRegistry.getSkills();
        }

        CapabilitySearchAgent.Analysis analysis = null;
        if (searchAgent != null) {
            analysis = searchAgent.selectTopTools(task, skills, candidates, maxTools, state);
        } else if (!candidates.isEmpty()) {
            List<String> toolNames = candidates.stream()
                .map(Capability::name)
                .limit(maxTools)
                .toList();
            analysis = new CapabilitySearchAgent.Analysis(
                "Fallback: no LLM selector available - using vector search results",
                List.of(),
                toolNames,
                "Vector search returned " + toolNames.size() + " candidates",
                List.of(),
                candidates.size()
            );
        }

        if (analysis == null) {
            analysis = new CapabilitySearchAgent.Analysis(
                "No capabilities found",
                List.of(),
                List.of(),
                "No tools or skills matched the query",
                List.of(),
                0
            );
        }

        return ToolResult.success(analysis.toJson());
    }

    private String extractTask(String json) {
        return extractString(json, "task");
    }

    private String extractTenant(String json) {
        return extractString(json, "tenant");
    }

    private static String extractString(String json, String field) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode n = node.get(field);
            return n != null && !n.isNull() ? n.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String errorJson(String message) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("error", message);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return "{\"error\": \"" + message + "\"}";
        }
    }

    public static CapabilitySearchTool createForSubagent(CapabilitySearch capabilitySearch,
                                                          ChatModel chatModel,
                                                          SkillRegistry skillRegistry) {
        CapabilitySearchAgent agent = chatModel != null 
            ? new CapabilitySearchAgent(chatModel) 
            : null;
        return new CapabilitySearchTool(capabilitySearch, agent, skillRegistry, DEFAULT_TOP_K, 3);
    }
}
