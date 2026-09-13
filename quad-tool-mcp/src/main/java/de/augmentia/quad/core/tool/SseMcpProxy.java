package de.augmentia.quad.core.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE-based MCP proxy.
 * Wraps an SSE MCP server and provides tools.
 */
public class SseMcpProxy {

    private final String sseUrl;
    private final String serverName;
    private final McpClient client;
    private final List<ToolMethod> tools = new CopyOnWriteArrayList<>();

    public SseMcpProxy(String sseUrl, String serverName) {
        this.sseUrl = sseUrl;
        this.serverName = serverName;
        
        HttpMcpTransport transport = HttpMcpTransport.builder()
            .sseUrl(sseUrl)
            .logRequests(false)
            .logResponses(false)
            .build();
            
        this.client = DefaultMcpClient.builder()
            .transport(transport)
            .clientName("quad-sse-proxy")
            .clientVersion("1.0")
            .build();
    }

    /**
     * Loads tools from the MCP server.
     */
    public List<ToolMethod> loadTools() {
        try {
            List<ToolSpecification> specs = client.listTools();
            tools.clear();
            for (ToolSpecification spec : specs) {
                String prefixed = "mcp_" + serverName + "_" + spec.name();
                ToolSpecification wrapped = ToolSpecification.builder()
                    .name(prefixed)
                    .description((spec.description() != null 
                        ? spec.description() : "") + " (MCP: " + serverName + ")")
                    .parameters(spec.parameters())
                    .build();
                tools.add(new McpToolMethod(wrapped, client, spec.name()));
            }
        } catch (Exception e) {
            System.err.println("Failed to load tools from MCP: " + e.getMessage());
        }
        return Collections.unmodifiableList(tools);
    }

    public List<ToolMethod> getTools() { return loadTools(); }
    public McpClient getClient() { return client; }
    public String getServerName() { return serverName; }
    public String getSseUrl() { return sseUrl; }
}