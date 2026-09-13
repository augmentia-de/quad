package de.augmentia.quad.core.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import de.augmentia.quad.core.session.AgentSessionState;

public class McpToolMethod implements ToolMethod {

    private final ToolSpecification spec;
    private final McpClient client;
    private final String originalToolName;

    public McpToolMethod(ToolSpecification spec, McpClient client, String originalToolName) {
        this.spec = spec;
        this.client = client;
        this.originalToolName = originalToolName;
    }

    @Override
    public ToolSpecification spec() {
        return spec;
    }

    @Override
    public ToolResult execute(String jsonArguments, AgentSessionState state) throws Exception {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
            .id(originalToolName + "-" + System.nanoTime())
            .name(originalToolName)
            .arguments(jsonArguments)
            .build();
        var result = client.executeTool(request);
        return ToolResult.success(result.resultText());
    }
}