package de.augmentia.quad.core.tool;

import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.agent.tool.ToolSpecification;

public class GizmoInvoker implements ToolMethod {
    private final ToolSpecification spec;
    private final String defaultResult;

    public GizmoInvoker(ToolSpecification spec, String defaultResult) {
        this.spec = spec;
        this.defaultResult = defaultResult;
    }

    @Override
    public ToolSpecification spec() { return spec; }

    @Override
    public ToolResult execute(String jsonArguments, AgentSessionState state) {
        return ToolResult.success(defaultResult);
    }
}