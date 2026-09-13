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
        return registry.getSpecifications().stream()
            .map(spec -> (ToolSpecification) spec)
            .toList();
    }

    public ToolMethod getTool(String name) {
        return registry.get(name);
    }

    public boolean isDynamic() {
        return true;
    }

    public Function<String, String> createToolInvoker(ToolMethod tool) {
        return jsonArgs -> {
            // This will be called with session state from the caller
            return jsonArgs;
        };
    }
}