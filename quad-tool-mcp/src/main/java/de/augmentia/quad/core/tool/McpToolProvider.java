package de.augmentia.quad.core.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class McpToolProvider implements BuiltInToolProvider {

    private static final Logger log = Logger.getLogger(McpToolProvider.class);
    private static final String DEFAULT_CONFIG_PATH = "config/mcp-config.json";

    @Override
    public String providerName() {
        return "mcp";
    }

    @Override
    public List<ToolMethod> getTools() {
        List<McpToolClient> clients = loadClients();
        List<ToolMethod> tools = new ArrayList<>();
        for (McpToolClient client : clients) {
            try {
                for (ToolSpecification spec : client.listTools()) {
                    String name = "mcp_" + client.serverName() + "_" + spec.name();
                    ToolSpecification prefixed = ToolSpecification.builder()
                        .name(name)
                        .description(spec.description() + " (from MCP: " + client.serverName() + ")")
                        .parameters(spec.parameters())
                        .build();
                    tools.add(new McpToolMethod(prefixed, client.client(), spec.name()));
                }
            } catch (Exception e) {
                log.error("Failed to load tools from MCP server: " + client.serverName(), e);
            }
        }
        if (!tools.isEmpty()) {
            log.infof("McpToolProvider: loaded %d tools from %d servers", tools.size(), clients.size());
        }
        return tools;
    }

    private List<McpToolClient> loadClients() {
        // 1. Try JSON config file
        String configPath = System.getProperty("quad.mcp.config-path", DEFAULT_CONFIG_PATH);
        List<McpToolClient> clients = McpToolClient.fromConfigFile(configPath);
        if (!clients.isEmpty()) {
            return clients;
        }
        // 2. Fallback: system properties (application.properties / env)
        return McpToolClient.fromSystemProperties();
    }

    public ToolMethod getToolByName(String name) {
        return getTools().stream()
            .filter(tool -> tool.spec().name().equals(name))
            .findFirst()
            .orElse(null);
    }
}