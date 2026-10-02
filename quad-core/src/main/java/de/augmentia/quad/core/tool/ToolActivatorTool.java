package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * Dynamic tool activation: registers/deregisters tools at runtime.
 * Recommended by capability_search and invoked by the agent.
 *
 * <p>Failure paths return {@link ToolResult#error(String)}. Wrapping them in
 * {@code ToolResult.success} made them indistinguishable from an actual activation, so the model
 * had no way to tell "activated" from "you passed a bad action" and simply repeated the call.
 */
public class ToolActivatorTool implements ToolMethod {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QuadToolRegistry registry;
    private final java.util.Map<String, ToolMethod> available = new java.util.concurrent.ConcurrentHashMap<>();
    private final ToolSpecification spec;

    public ToolActivatorTool(QuadToolRegistry registry) {
        this.registry = registry;
        this.spec = ToolSpecification.builder()
            .name("tool_activator")
            .description("Activate or deactivate a tool by name. "
                + "Use action=\"add\" to make a tool available, action=\"remove\" to hide it.")
            // The model is expected to send `action` and `tool`; without a schema it cannot know
            // that. Declared from one description so docs and schema cannot drift apart.
            .parameters(dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder()
                .addStringProperty("action", "Either \"add\" to activate a tool or \"remove\" to deactivate it.")
                .addStringProperty("tool", "Name of the tool to activate or deactivate.")
                .required("action", "tool")
                .build())
            .build();
    }

    /** Registers a tool that can be activated via tool_activator. */
    public void registerAvailable(String name, ToolMethod tool) {
        available.put(name, tool);
    }

    @Override
    public ToolSpecification spec() { return spec; }

    @Override
    public ToolMeta meta() {
        return ToolMeta.simple(spec.name(), spec.description());
    }

    @Override
    public ToolResult execute(String jsonArguments, AgentSessionState state) throws Exception {
        if (jsonArguments == null || jsonArguments.isBlank()) {
            return ToolResult.error("Missing arguments: both 'action' and 'tool' are required.");
        }
        var node = MAPPER.readTree(jsonArguments);
        var action = node.hasNonNull("action") ? node.get("action").asText() : null;
        var tool = node.hasNonNull("tool") ? node.get("tool").asText() : null;

        if (action == null || tool == null) {
            return ToolResult.error("Both 'action' and 'tool' parameters are required. "
                    + "Available tools: " + available.keySet());
        }

        return switch (action) {
            case "add" -> {
                var toolMethod = available.get(tool);
                if (toolMethod == null) {
                    yield ToolResult.error("Unknown tool '" + tool + "'. Available: " + available.keySet());
                }
                registry.register(tool, toolMethod);
                yield ToolResult.success("Tool '" + tool + "' activated.");
            }
            case "remove" -> {
                if (available.get(tool) == null) {
                    yield ToolResult.error("Unknown tool '" + tool + "'. Available: " + available.keySet());
                }
                registry.remove(tool);
                yield ToolResult.success("Tool '" + tool + "' deactivated.");
            }
            default -> ToolResult.error("Unknown action '" + action + "'. Use 'add' or 'remove'.");
        };
    }
}