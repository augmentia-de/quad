package de.augmentia.quad.core.agent.runtime;

import java.util.List;
import java.util.function.Function;

import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.ToolMethod;
import dev.langchain4j.agent.tool.ToolSpecification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DynamicToolProvider {
    @Inject
    private QuadToolRegistry registry;
    @Inject
    private ToolExecutor toolExecutor;

    public DynamicToolProvider(QuadToolRegistry registry, ToolExecutor toolExecutor) {
        this.registry = registry;
        this.toolExecutor = toolExecutor;
    }

    public List<ToolSpecification> getToolSpecifications() {
        return registry.getSpecifications(false);
    }

    public ToolMethod getTool(String name) {
        return registry.get(name);
    }

    public boolean isDynamic() {
        return true;
    }

    /**
     * @deprecated The returned function echoed its input back instead of a tool result, so every
     * caller would have received the raw JSON arguments as if they were the answer. Throws instead
     * of pretending; use {@link ToolExecutor#execute}.
     */
    @Deprecated(forRemoval = true)
    public Function<String, String> createToolInvoker(ToolMethod tool) {
        throw new UnsupportedOperationException(
                "createToolInvoker returned its arguments instead of a tool result. "
                        + "Invoke tools through ToolExecutor.execute(tool, jsonArguments, state).");
    }
}