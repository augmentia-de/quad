package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic tool activation: registers/deregisters tools at runtime.
 * Recommended by capability_search and invoked by the agent.
 */
public class ToolActivatorTool implements ToolMethod {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final QuadToolRegistry registry;
    private final Map<String, ToolMethod> available = new ConcurrentHashMap<>();
    private final ToolSpecification spec;

    public ToolActivatorTool(QuadToolRegistry registry) {
        this.registry = registry;
        this.spec = ToolSpecification.builder()
            .name("tool_activator")
            .description("Activate or deactivate a tool by name. "
                + "Use action=\"add\" to make a tool available, action=\"remove\" to hide it.")
            .build();
    }

    /** Registers a tool that can be activated via tool_activator. */
    public void registerAvailable(String name, ToolMethod tool) {
        available.put(name, tool);
    }

    @Override
    public ToolSpecification spec() { return spec; }

    @Override
    public ToolResult execute(String jsonArguments, AgentSessionState state) throws Exception {
        var node = MAPPER.readTree(jsonArguments);
        var action = node.has("action") ? node.get("action").asText() : null;
        var tool = node.has("tool") ? node.get("tool").asText() : null;

        if (action == null || tool == null)
            return ToolResult.success("Both 'action' and 'tool' parameters are required.");

        return switch (action) {
            case "add" -> {
                var toolMethod = available.get(tool);
                if (toolMethod == null)
                    yield ToolResult.success("Unknown tool '" + tool + "'. Available: " + available.keySet());
                registry.register(tool, toolMethod);
                yield ToolResult.success("Tool '" + tool + "' activated.");
            }
            case "remove" -> {
                registry.remove(tool);
                yield ToolResult.success("Tool '" + tool + "' deactivated.");
            }
            default -> ToolResult.success("Unknown action '" + action + "'. Use 'add' or 'remove'.");
        };
    }
}
