package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.runtime.AgentEventPublisher;
import de.augmentia.quad.core.capability.CapabilityRegistry;
import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.hook.pipeline.HookRegistry;
import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.List;
import java.util.Set;

/**
 * Dynamically creates sub-agents at runtime with arbitrary tools.
 * <p>
 * Unlike {@link SubAgentTool}, which wraps a predefined sub-agent,
 * {@code DynamicSubAgentTool} creates a fresh agent with only the requested
 * tools on each invocation. This enables flexible orchestrator patterns
 * where the parent agent controls the tool selection per step.
 * <p>
 * The tool signature is:
 * <pre>
 * {
 *   "prompt": "The task for the sub-agent",
 *   "tools": ["websearch", "read_file"]  // optional: if empty, all available tools
 * }
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>
 * DynamicSubAgentTool tool = new DynamicSubAgentTool(chatModel, fullRegistry);
 * registry.register("execute_step", tool);
 * </pre>
 *
 * @see SubAgentTool
 */
public class DynamicSubAgentTool implements ToolMethod {

    private static final int SUB_AGENT_MAX_ITERATIONS = 5;
    private static final String SUB_AGENT_SYSTEM_PROMPT = """
            You are a sub-agent. Execute the task assigned to you precisely.

            RULES:
            - Use at most 3 tool calls for research/actions
            - Collect results as structured findings
            - Respond at the end with a concrete text result (never empty)
            """;
    private static final int DEFAULT_MAX_DEPTH = 5;
    private static final Set<String> FORBIDDEN_TOOLS = Set.of("execute_step", "capability_search");
    private static final ThreadLocal<Integer> RECURSION_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final QuadToolRegistry fullRegistry;
    private final CapabilityRegistry capabilityRegistry;
    private final int maxDepth;
    private final ToolSpecification spec;
    private HookRegistry hookRegistry;
    private AgentEventPublisher eventPublisher;

    public void setHookRegistry(HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    public void setEventPublisher(AgentEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public DynamicSubAgentTool(ChatModel chatModel, QuadToolRegistry fullRegistry) {
        this(chatModel, fullRegistry, null, DEFAULT_MAX_DEPTH);
    }

    public DynamicSubAgentTool(ChatModel chatModel, QuadToolRegistry fullRegistry, int maxDepth) {
        this(chatModel, fullRegistry, null, maxDepth);
    }

    public DynamicSubAgentTool(ChatModel chatModel, QuadToolRegistry fullRegistry,
                               CapabilityRegistry capabilityRegistry) {
        this(chatModel, fullRegistry, capabilityRegistry, DEFAULT_MAX_DEPTH);
    }

    public DynamicSubAgentTool(ChatModel chatModel, QuadToolRegistry fullRegistry,
                               CapabilityRegistry capabilityRegistry, int maxDepth) {
        if (chatModel == null) throw new IllegalArgumentException("chatModel must not be null");
        if (fullRegistry == null) throw new IllegalArgumentException("fullRegistry must not be null");
        if (maxDepth <= 0) throw new IllegalArgumentException("maxDepth must be > 0");

        this.chatModel = chatModel;
        this.fullRegistry = fullRegistry;
        this.capabilityRegistry = capabilityRegistry;
        this.maxDepth = maxDepth;
        this.spec = ToolSpecification.builder()
                .name("execute_step")
                .description("Executes a task by creating a temporary sub-agent. "
                        + "You MUST provide the 'prompt' parameter with the task description. "
                        + "Optionally provide 'tools' as a JSON array of tool names "
                        + "(e.g. [\"websearch\",\"read_file\"]). "
                        + "If 'tools' is omitted, all available tools are used. "
                        + "Optionally provide 'skills' as a JSON array of skill names "
                        + "(e.g. [\"financial-analysis\"]) to inject skill instructions into the sub-agent.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("prompt", "The concrete task for the sub-agent to execute. REQUIRED.")
                        .addProperty("tools", JsonArraySchema.builder()
                                .items(new JsonStringSchema())
                                .description("Tool names the sub-agent should use, "
                                        + "e.g. [\"websearch\",\"read_file\"]. Optional.")
                                .build())
                        .addProperty("skills", JsonArraySchema.builder()
                                .items(new JsonStringSchema())
                                .description("Skill names to inject into the sub-agent system prompt, "
                                        + "e.g. [\"financial-analysis\"]. Optional.")
                                .build())
                        .required("prompt")
                        .build())
                .build();
    }

    @Override
    public ToolSpecification spec() {
        return spec;
    }

    @Override
    public ToolResult execute(String jsonArguments, AgentSessionState state) throws Exception {
        int currentDepth = RECURSION_DEPTH.get();
        if (currentDepth >= maxDepth) {
            return ToolResult.error("Error: maximum dynamic sub-agent recursion depth of " + maxDepth + " reached.");
        }

        String prompt = parsePrompt(jsonArguments);
        List<String> toolNames = parseTools(jsonArguments);
        List<String> skillNames = parseSkills(jsonArguments);

        Agent subAgent = createSubAgent(toolNames, skillNames);
        List<String> availableTools = subAgent.getToolRegistry().getAll().stream()
                .map(t -> t.spec().name()).toList();

        System.out.println("[execute_step] depth=" + currentDepth
                + " prompt=" + truncate(prompt, 100)
                + " tools=" + availableTools
                + " skills=" + skillNames);

        RECURSION_DEPTH.set(currentDepth + 1);
        try {
            String result = subAgent.executeReAct(prompt, state);

            // Automatically save as finding so the orchestrator
            // can retrieve results via get_results
            if (result != null && !result.isBlank() && !result.startsWith("Error")) {
                state.addFinding("[step] " + result);
            }

            System.out.println("[execute_step] result=" + truncate(result, 200)
                    + " findings=" + state.findings().size());

            return ToolResult.success(result);
        } catch (Exception e) {
            System.out.println("[execute_step] ERROR: " + e.getMessage());
            return ToolResult.error("Error in dynamic sub-agent: " + e.getMessage());
        } finally {
            RECURSION_DEPTH.set(currentDepth);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...(" + s.length() + " chars)";
    }

    /**
     * Creates a fresh sub-agent with the requested tools.
     * <p>
     * Orchestrator tools ({@code execute_step}, {@code capability_search})
     * are always excluded to prevent recursion.
     * If no tool names were provided, all available tools
     * (minus orchestrator tools) are used.
     */
    private Agent createSubAgent(List<String> toolNames, List<String> skillNames) {
        QuadToolRegistry agentRegistry = new QuadToolRegistry(
                new ToolArgsMapper(new ObjectMapper()));

        if (toolNames.isEmpty()) {
            for (ToolMethod tool : fullRegistry.getAll()) {
                if (!FORBIDDEN_TOOLS.contains(tool.spec().name())) {
                    agentRegistry.register(tool.spec().name(), tool);
                }
            }
        } else {
            for (String name : toolNames) {
                if (FORBIDDEN_TOOLS.contains(name)) continue;
                ToolMethod tool = fullRegistry.get(name);
                if (tool != null) {
                    agentRegistry.register(name, tool);
                }
            }
        }

        String toolList = buildToolList(agentRegistry);
        String skillBlock = buildSkillBlock(skillNames);

        Agent subAgent = new Agent() {
            @Override
            protected AgentSessionState newSessionState() {
                return new AgentSessionState();
            }

            @Override
            public List<ChatMessage> initialMessages(String prompt, AgentSessionState state) {
                String systemPrompt = SUB_AGENT_SYSTEM_PROMPT
                        + "\n\nAVAILABLE TOOLS:\n" + toolList;
                if (!skillBlock.isEmpty()) {
                    systemPrompt += "\n\n" + skillBlock;
                }
                return List.of(
                        new SystemMessage(systemPrompt),
                        new dev.langchain4j.data.message.UserMessage(prompt));
            }
        };
        subAgent.setLlm(chatModel);
        subAgent.setToolRegistry(agentRegistry);
        subAgent.setReActMaxIterations(SUB_AGENT_MAX_ITERATIONS);
        if (hookRegistry != null) subAgent.setHookRegistry(hookRegistry);
        if (eventPublisher != null) subAgent.setEventPublisher(eventPublisher);

        return subAgent;
    }

    private String buildToolList(QuadToolRegistry registry) {
        StringBuilder sb = new StringBuilder();
        for (ToolMethod tool : registry.getAll()) {
            sb.append("- ").append(tool.spec().name()).append(": ")
              .append(tool.spec().description()).append("\n");
        }
        return sb.toString();
    }

    private String buildSkillBlock(List<String> skillNames) {
        if (skillNames.isEmpty() || capabilityRegistry == null) return "";
        StringBuilder sb = new StringBuilder("ACTIVATED SKILLS:\n");
        for (String name : skillNames) {
            Skill skill = capabilityRegistry.getSkill(name);
            if (skill != null) {
                sb.append("\n### ").append(skill.name()).append("\n");
                sb.append(skill.instructions() != null ? skill.instructions() : skill.description());
                sb.append("\n");
                if (skill.allowedTools() != null && !skill.allowedTools().isEmpty()) {
                    sb.append("Allowed tools: ").append(String.join(", ", skill.allowedTools())).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String parsePrompt(String jsonArguments) {
        if (jsonArguments == null || jsonArguments.isBlank()) return "";
        try {
            JsonNode root = MAPPER.readTree(jsonArguments);
            JsonNode prompt = root.get("prompt");
            return prompt != null ? prompt.asText() : "";
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse prompt: " + e.getMessage(), e);
        }
    }

    private List<String> parseTools(String jsonArguments) {
        return parseStringArray(jsonArguments, "tools");
    }

    private List<String> parseSkills(String jsonArguments) {
        return parseStringArray(jsonArguments, "skills");
    }

    private List<String> parseStringArray(String jsonArguments, String field) {
        if (jsonArguments == null || jsonArguments.isBlank()) return List.of();
        try {
            JsonNode root = MAPPER.readTree(jsonArguments);
            JsonNode node = root.get(field);
            if (node == null || !node.isArray() || node.isEmpty()) {
                return List.of();
            }
            java.util.List<String> result = new java.util.ArrayList<>();
            for (JsonNode t : node) {
                result.add(t.asText());
            }
            return List.copyOf(result);
        } catch (Exception e) {
            return List.of();
        }
    }
}
