package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Delegates execution to a sub-agent.
 * <p>
 * The tool appears automatically in the parent agent's offered tool
 * specifications via {@link ToolMethod#spec()}. When invoked, the sub-agent
 * executes the same prompt via {@link Agent#executeReAct} (ReAct flow with
 * access to its own tools).
 * <p>
 * If the sub-agent does not yet have a {@link QuadToolRegistry},
 * one is created automatically and its {@code @Tool} methods are registered.
 * This means it is sufficient to create an agent with {@code @Tool} methods and
 * pass it as a sub-agent — the tool setup is automatic.
 * <p>
 * The sub-agent shares the {@link AgentSessionState} of the caller
 * (shared session state), so findings, CWD, and saga fields remain visible.
 * A ThreadLocal recursion depth prevents infinite loops in nested
 * agent-to-agent calls.
 */
public class SubAgentTool implements ToolMethod {

    public static final int DEFAULT_MAX_DEPTH = 5;

    private static final ThreadLocal<Integer> RECURSION_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Agent subAgent;
    private final String name;
    private final String description;
    private final int maxDepth;
    private final ToolSpecification spec;

    public SubAgentTool(Agent subAgent, String name) {
        this(subAgent, name, "Executes a specialized sub-agent: " + name, DEFAULT_MAX_DEPTH);
    }

    public SubAgentTool(Agent subAgent, String name, String description) {
        this(subAgent, name, description, DEFAULT_MAX_DEPTH);
    }

    public SubAgentTool(Agent subAgent, String name, String description, int maxDepth) {
        if (subAgent == null) throw new IllegalArgumentException("subAgent must not be null");
        if (maxDepth <= 0) throw new IllegalArgumentException("maxDepth must be > 0");
        this.subAgent = subAgent;
        this.name = name;
        this.description = description;
        this.maxDepth = maxDepth;
        this.spec = ToolSpecification.builder()
                .name(name)
                .description(description)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("prompt", "The prompt to delegate to the sub-agent")
                        .required("prompt")
                        .build())
                .build();

        // Sub-agent: automatically set up tool registry if not yet present
        if (subAgent.getToolRegistry() == null) {
            QuadToolRegistry registry = new QuadToolRegistry(new ToolArgsMapper(new ObjectMapper()));
            registry.registerFromAgent(subAgent);
            subAgent.setToolRegistry(registry);
        }
    }

    @Override
    public ToolSpecification spec() {
        return spec;
    }

    @Override
    public ToolResult execute(String jsonArguments, AgentSessionState state) {
        int currentDepth = RECURSION_DEPTH.get();
        if (currentDepth >= maxDepth) {
            return ToolResult.error("Error in sub-agent: maximum recursion depth of " + maxDepth + " reached.");
        }

        String prompt = parsePrompt(jsonArguments);

        RECURSION_DEPTH.set(currentDepth + 1);
        try {
            return ToolResult.success(subAgent.executeReAct(prompt, state));
        } catch (Exception e) {
            return ToolResult.error("Error in sub-agent: " + e.getMessage());
        } finally {
            RECURSION_DEPTH.set(currentDepth);
        }
    }

    private String parsePrompt(String jsonArguments) {
        if (jsonArguments == null || jsonArguments.isBlank()) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(jsonArguments);
            JsonNode prompt = root.get("prompt");
            return prompt != null ? prompt.asText() : "";
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse arguments for sub-agent tool '" + name + "': " + e.getMessage(), e);
        }
    }

    public Agent getSubAgent() {
        return subAgent;
    }

    public String getToolName() {
        return name;
    }
}
